#!/usr/bin/env python3
"""Walk Maven POM/module graphs and mirror artifacts into a local file repo.
Resumable: resolution result is checkpointed to RESOLVED.json; mirroring skips
files that already exist."""
import urllib.request, urllib.error, os, re, sys, json, threading
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(os.path.dirname(HERE), 'localrepo')
CKPT = os.path.join(HERE, 'RESOLVED.json')
BASES = ['https://repo.maven.apache.org/maven2', 'https://dl.google.com/dl/android/maven2']
NS = '{http://maven.apache.org/POM/4.0.0}'

_cache = {}
_cache_lock = threading.Lock()

DISKCACHE = os.path.join(HERE, 'httpcache')
os.makedirs(DISKCACHE, exist_ok=True)

def fetch(url):
    import hashlib
    key = hashlib.sha1(url.encode()).hexdigest()
    cpath = os.path.join(DISKCACHE, key)
    if os.path.exists(cpath):
        if os.path.getsize(cpath) == 0:
            return None
        with open(cpath, 'rb') as f:
            return f.read()
    with _cache_lock:
        if url in _cache:
            return _cache[url]
    cacheable = True
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'mvnwalk'})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
    except urllib.error.HTTPError:
        data = None  # genuine HTTP error (e.g. 404): safe to negative-cache
    except Exception:
        data = None
        cacheable = False  # transient failure: do not cache
    if cacheable:
        with open(cpath, 'wb') as f:
            if data:
                f.write(data)
    with _cache_lock:
        _cache[url] = data
    return data

def gpath(g):
    return g.replace('.', '/')

def fetch_any(g, a, v, fname):
    for base in BASES:
        data = fetch(f'{base}/{gpath(g)}/{a}/{v}/{fname}')
        if data is not None:
            return data
    return None

def txt(el, tag):
    c = el.find(f'{NS}{tag}')
    return c.text.strip() if c is not None and c.text else None

def interp(s, props, depth=0):
    if s is None or depth > 10:
        return s
    def rep(m):
        return props.get(m.group(1), m.group(0))
    out = re.sub(r'\$\{([^}]+)\}', rep, s)
    return interp(out, props, depth + 1) if '${' in out and out != s else out

def ver_key(v):
    key = []
    for p in re.split(r'[.\-_]', v):
        key.append((1, int(p), '') if p.isdigit() else (0, 0, p))
    return key

def newest(vs):
    vs = [v for v in vs if v and not v.startswith('[') and not v.startswith('(')]
    return max(vs, key=ver_key) if vs else None

class Pom:
    def __init__(self, g, a, v):
        self.g, self.a, self.v = g, a, v
        self.props = {}
        self.depmgmt = {}
        self.deps = []
        self.packaging = 'jar'
        self.parent = None

def parse_dep(dep, props):
    g, a = txt(dep, 'groupId'), txt(dep, 'artifactId')
    if not g or not a:
        return None
    excl = set()
    ex_el = dep.find(f'{NS}exclusions')
    if ex_el is not None:
        for e in ex_el.findall(f'{NS}exclusion'):
            excl.add((txt(e, 'groupId'), txt(e, 'artifactId')))
    return {'g': interp(g, props), 'a': interp(a, props),
            'v': interp(txt(dep, 'version'), props),
            'scope': txt(dep, 'scope') or 'compile',
            'type': txt(dep, 'type') or 'jar',
            'optional': (txt(dep, 'optional') or 'false') == 'true',
            'exclusions': excl}

def apply_depmgmt(d, depmgmt):
    m = depmgmt.get((d['g'], d['a']))
    if m:
        if not d['v']:
            d['v'] = m['v']
        if d['scope'] == 'compile' and m.get('scope') and m['scope'] != 'import':
            d['scope'] = m['scope']
        if d['type'] == 'jar' and m.get('type'):
            d['type'] = m['type']
        d['exclusions'] = d['exclusions'] | m.get('exclusions', set())
    return d

def load_pom(g, a, v, seen=None):
    data = fetch_any(g, a, v, f'{a}-{v}.pom')
    if data is None:
        return None
    root = ET.fromstring(data)
    pom = Pom(g, a, v)
    pom.packaging = txt(root, 'packaging') or 'jar'
    parent_el = root.find(f'{NS}parent')
    parent_pom = None
    if parent_el is not None:
        pg, pa, pv = txt(parent_el, 'groupId'), txt(parent_el, 'artifactId'), txt(parent_el, 'version')
        if pg and pa and pv:
            pom.parent = (pg, pa, pv)
            seen = seen or set()
            if (pg, pa, pv) not in seen:
                seen.add((pg, pa, pv))
                parent_pom = load_pom(pg, pa, pv, seen)
    props = {}
    if parent_pom:
        props.update(parent_pom.props)
    props['project.groupId'] = txt(root, 'groupId') or (parent_pom.g if parent_pom else g)
    props['project.artifactId'] = a
    props['project.version'] = txt(root, 'version') or v
    props['pom.version'] = props['project.version']
    props['version'] = props['project.version']
    props_el = root.find(f'{NS}properties')
    if props_el is not None:
        for child in props_el:
            tag = child.tag.replace(NS, '')
            if child.text:
                props[tag] = child.text.strip()
    pom.props = props
    depmgmt = {}
    if parent_pom:
        depmgmt.update(parent_pom.depmgmt)
    dm_el = root.find(f'{NS}dependencyManagement/{NS}dependencies')
    if dm_el is not None:
        for dep in dm_el.findall(f'{NS}dependency'):
            entry = parse_dep(dep, props)
            if entry is None:
                continue
            if entry.get('scope') == 'import' and entry.get('type') == 'pom':
                bom = load_pom(entry['g'], entry['a'], entry['v'])
                if bom:
                    for k, val in bom.depmgmt.items():
                        depmgmt.setdefault(k, val)
                register(entry["g"], entry["a"], entry["v"], pom_only_flag=True)
            else:
                depmgmt[(entry['g'], entry['a'])] = entry
    pom.depmgmt = depmgmt
    deps = []
    if parent_pom:
        deps.extend(parent_pom.deps)
    deps_el = root.find(f'{NS}dependencies')
    if deps_el is not None:
        for dep in deps_el.findall(f'{NS}dependency'):
            entry = parse_dep(dep, props)
            if entry:
                deps.append(entry)
    pom.deps = [apply_depmgmt(d, depmgmt) for d in deps]
    return pom

candidates = {}
chosen = {}
processed = {}
pom_only = set()
need_files = {}
lock = threading.Lock()

def resolve_range(g, a, rng):
    for base in BASES:
        data = fetch(f'{base}/{gpath(g)}/{a}/maven-metadata.xml')
        if data:
            root = ET.fromstring(data)
            vers = [e.text for e in root.iter(f'{NS}version')]
            lo = re.search(r'[\[(]([^,\])]*)', rng)
            hi = re.search(r',([^\])]*)[\])]', rng)
            lo_v = lo.group(1) if lo else None
            hi_v = hi.group(1) if hi else None
            ok = [v for v in vers
                  if not (lo_v and ver_key(v) < ver_key(lo_v))
                  and not (hi_v and ver_key(v) > ver_key(hi_v))]
            if ok:
                return ok[-1]
    return None

def register(g, a, v, pom_only_flag=False):
    if v is None:
        return
    if v.startswith('[') or v.startswith('('):
        v = resolve_range(g, a, v)
        if not v:
            return
    with lock:
        if pom_only_flag:
            pom_only.add((g, a, v))
        c = candidates.setdefault((g, a), set())
        c.add(v)

def process_module_json(g, a, v):
    data = fetch_any(g, a, v, f'{a}-{v}.module')
    if data is None:
        return
    try:
        mj = json.loads(data)
    except Exception:
        return
    files = set()
    for variant in mj.get('variants', []):
        for dep in variant.get('dependencies', []):
            dv = dep.get('version', {})
            ver = dv.get('strictly') or dv.get('requires') or dv.get('prefers')
            if ver and not dep.get('excluded'):
                register(dep['group'], dep['module'], ver)
        for f in variant.get('files', []):
            if f.get('name'):
                files.add(f['name'])
        for art in variant.get('artifacts', []):
            if art.get('name'):
                files.add(art['name'])
    if files:
        with lock:
            need_files.setdefault((g, a, v), set()).update(files)

def resolve(seeds):
    for (g, a, v) in seeds:
        register(g, a, v)
    for rounds in range(40):
        changed = False
        for key, vs in list(candidates.items()):
            nv = newest(vs)
            if chosen.get(key) != nv:
                chosen[key] = nv
                changed = True
        todo = [(g, a, chosen[(g, a)]) for (g, a) in list(chosen)
                if processed.get((g, a)) != chosen[(g, a)]]
        print(f'round {rounds}: {len(chosen)} modules, {len(todo)} to process', flush=True)
        if not todo and not changed:
            break
        for (g, a, v) in todo:
            processed[(g, a)] = v
            pom = load_pom(g, a, v)
            if pom is None:
                print(f'WARN: no POM for {g}:{a}:{v}', flush=True)
                continue
            with lock:
                pom_only.add((g, a, v))
                if pom.parent:
                    pom_only.add(pom.parent)
            process_module_json(g, a, v)
            for d in pom.deps:
                if d['scope'] in ('test', 'provided', 'system', 'import'):
                    continue
                if d['optional']:
                    continue
                if (d['g'], d['a']) == (g, a):
                    continue
                register(d['g'], d['a'], d['v'], pom_only_flag=(d['type'] == 'pom'))
    print(f'resolved {len(chosen)} modules', flush=True)

def mirror_one(gav):
    g, a, v = gav
    destdir = os.path.join(ROOT, gpath(g), a, v)
    os.makedirs(destdir, exist_ok=True)
    pom = load_pom(g, a, v)
    packaging = pom.packaging if pom else 'jar'
    names = {f'{a}-{v}.pom'}
    if fetch_any(g, a, v, f'{a}-{v}.module') is not None:
        names.add(f'{a}-{v}.module')
    exts = {'aar': 'aar', 'jar': 'jar', 'bundle': 'jar', 'maven-plugin': 'jar'}
    main = f'{a}-{v}.{exts.get(packaging, "jar")}'
    if packaging != 'pom':
        names.add(main)
    names |= need_files.get(gav, set())
    ok = True
    for name in names:
        dest = os.path.join(destdir, name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            continue
        data = fetch_any(g, a, v, name)
        if data is None:
            if name == main:
                print(f'MISSING main artifact {g}:{a}:{v} -> {name}', flush=True)
                ok = False
            continue
        with open(dest, 'wb') as f:
            f.write(data)
    return ok

SEEDS = [
    ('com.android.tools.build', 'gradle', '8.5.2'),
    ('org.jetbrains.kotlin', 'kotlin-gradle-plugin', '2.0.20'),
    ('androidx.media3', 'media3-exoplayer', '1.4.1'),
    ('androidx.media3', 'media3-exoplayer-hls', '1.4.1'),
    ('androidx.media3', 'media3-ui', '1.4.1'),
    ('com.github.bumptech.glide', 'glide', '4.16.0'),
    ('com.github.bumptech.glide', 'compiler', '4.16.0'),
    ('com.squareup.okhttp3', 'okhttp', '4.12.0'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-android', '1.8.1'),
    ('androidx.appcompat', 'appcompat', '1.7.0'),
    ('androidx.recyclerview', 'recyclerview', '1.3.2'),
    ('androidx.core', 'core-ktx', '1.13.1'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.20'),
]

def main():
    seeds = list(SEEDS)
    extra = os.environ.get('EXTRA_SEEDS', '')
    for s in extra.split(';'):
        if s.strip():
            g, a, v = s.strip().split(':')
            seeds.append((g, a, v))
    if os.path.exists(CKPT) and not os.environ.get('FORCE_RESOLVE') and not extra:
        ck = json.load(open(CKPT))
        for g, a, v in ck['gavs']:
            pom_only.add((g, a, v))
        for g, a, v in ck['need_files']:
            need_files.setdefault(tuple(v[:3]) if False else (g, a, v), set())
        need_files.clear()
        for key, files in ck['need_files_map'].items():
            g, a, v = key.split('\x00')
            need_files[(g, a, v)] = set(files)
        allgavs = set(pom_only)
        print(f'loaded checkpoint: {len(allgavs)} artifacts')
    else:
        resolve(seeds)
        allgavs = set()
        # Mirror EVERY requested version, not just the newest: Gradle module
        # metadata variants can pin older versions for specific configurations.
        for (g, a), vs in candidates.items():
            for v in vs:
                allgavs.add((g, a, v))
        allgavs |= pom_only
        ck = {'gavs': sorted(allgavs),
              'need_files_map': {'\x00'.join(k): sorted(v) for k, v in need_files.items()},
              'need_files': []}
        json.dump(ck, open(CKPT, 'w'))
        print(f'checkpoint written: {len(allgavs)} artifacts')
    print(f'mirroring {len(allgavs)} artifacts...', flush=True)
    fails = 0
    done = 0
    with ThreadPoolExecutor(max_workers=8) as ex:
        for ok in ex.map(mirror_one, sorted(allgavs)):
            done += 1
            if not ok:
                fails += 1
            if done % 50 == 0:
                print(f'  mirrored {done}/{len(allgavs)}', flush=True)
    print(f'done, main-artifact failures: {fails}')

if __name__ == '__main__':
    main()
