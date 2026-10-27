# MiTV for Fire TV — Build Report (v1.0.0)

**Result: BUILD SUCCESSFUL.** A signed release APK was produced and verified.

| Item | Value |
|---|---|
| APK | `MiTV-FireTV-v1.0.0.apk` (this directory) |
| Size | 6,026,042 bytes (5.8 MiB) |
| Package | `com.mitv.player` |
| Version | versionName `1.0.0`, versionCode `1` |
| SDKs | minSdk 24 · targetSdk 34 · compileSdk 34 |
| App label | MiTV, landscape, `LAUNCHER` + `LEANBACK_LAUNCHER`, `android:banner` set, leanback **not** required (also runs on non-TV Android) |

## Signing

- Keystore: `keystore/mitv-release.jks`, alias `mitv`, RSA 2048, 25-year validity,
  DN `CN=MiTV, OU=Personal, O=MiTV, L=West Palm Beach, ST=FL, C=US`.
- **Certificate SHA-256: `a9b987157c0a554e976fddfd4274da5db478d6228133f2b78da8b6ce996f8fff`**
  (verified with `apksigner verify --print-certs` on the final APK — matches the
  fingerprint recorded when the key was generated).
- Passwords live only in `keystore/KEYSTORE-INFO.txt` (chmod 600).
- ⚠️ Correction recorded in that file: the keystore is **PKCS12** format, so the key
  password is identical to the store password; the separate key password written
  down at generation time had never actually been applied by keytool. The file was
  fixed (the key itself was **not** regenerated — it is the app's permanent identity).

## Toolchain used

- Gradle 8.7 (wrapper included in the project) · Android Gradle Plugin 8.5.2
- Kotlin 2.0.20 (+ kapt) · JDK Temurin 21.0.12.1 (at `~/jdk21`)
- Android SDK: platform `android-34` (base platform), build-tools 34.0.0 (`~/android-sdk`)
- Libraries: Media3/ExoPlayer 1.4.1 (exoplayer, exoplayer-hls, ui), Glide 4.16.0,
  OkHttp 4.12.0, kotlinx-coroutines-android 1.8.1, appcompat 1.7.0,
  recyclerview 1.3.2, core-ktx 1.13.1

## How the build works here (important environment note)

The build is **fully offline**. This VM's egress proxy resets TLS connections from
the Gradle daemon (Java/JSSE fingerprint) and the runtime denies direct TCP from
detached processes, so Gradle cannot download from public repositories. All
dependencies (≈340 artifacts, 517 MB) were mirrored into `localrepo/` by
`tools/mvnwalk.py`, and `settings.gradle.kts` resolves **only** from that file
repository. Rebuild with:

```
tools/build.sh assembleRelease      # gradle --offline against localrepo/
```

`tools/iterate.sh` automates the "build → mirror any missing module → rebuild"
loop. To build elsewhere with normal internet, point `settings.gradle.kts`
repositories back to `google()` / `mavenCentral()` / `gradlePluginPortal()`.

## Verification performed

1. `gradle assembleRelease` → **BUILD SUCCESSFUL** (50 tasks; Kotlin compile,
   KAPT for Glide, dexing, resource packaging, signing).
2. `apksigner verify --print-certs` on the final APK → signature valid, SHA-256
   matches the keystore fingerprint above.
3. `aapt2 dump badging` → package `com.mitv.player`, versionName 1.0.0,
   versionCode 1, minSdk 24, targetSdk 34, launchable + leanback-launchable
   `MainActivity`, `android.software.leanback` not required.
4. Manifest inspection → `android:icon` and `android:banner` present.
5. **Not** runtime-tested: no device or emulator is available in this
   environment. Installing on the Fire TV Stick is a separate, separately
   authorized step and has **not** been done.

## Feature status (per the v1 scope, items 1–13)

1. **Playlists** — implemented. Ajustes → Listas: add/edit/delete Xtream
   (name, server, user, password) and M3U (name, URL, optional EPG URL);
   exactly one active; persisted as JSON in internal storage mirroring the
   Windows `playlists.json` fields; first-run prompt when none exist.
2. **Content loading** — implemented, following the Windows app's logic:
   Xtream `player_api.php` (live/VOD/series categories + streams; standard
   `/live/`, `/movie/`, `/series/` URL patterns; lazy `get_vod_info` /
   `get_series_info`), M3U `#EXTINF` parsing (tvg-id, tvg-logo, group-title;
   movie/series classification by group/extension as in Windows; `###`
   junk channels skipped). Content cached to disk, refreshed in background.
3. **TV en vivo** — implemented per the approved mockup: category list with
   counts (🕘 Historial first, ★ Favoritos, Todo, then groups), numbered
   channel rows with ☆/★, logo on white rounded tile, current programme +
   progress bar + times from EPG, right preview panel with muted autoplay
   after a 600 ms focus dwell, OK = fullscreen, Menu key = toggle favorite.
4. **Deportes** — implemented: same live screen filtered to categories whose
   name contains "sport"/"deporte" (Windows rule; they also stay in TV en vivo).
5. **Películas** — implemented: category sidebar + 5-column poster grid,
   detail view (poster, title, category, plot/rating/director/cast loaded
   lazily via `get_vod_info`), Continuar/Desde el inicio, ★ favorito, ✓ visto.
6. **Series** — implemented: sidebar + grid, detail with seasons/episodes,
   per-episode ✓/⏵ progress, episode playback.
7. **Player** — implemented: Media3 ExoPlayer; play/pause (media key and OK),
   ±10 s seek for VOD/series (live has no seek), aspect/resize cycling,
   audio and subtitle track dialogs (subtitle button labelled
   "💬 Subtítulos" — never "CC"), Back exits, position memory so Continuar works.
8. **Home** — implemented per mockup: Continuar viendo (in-progress 3%–95%
   with progress), Canales destacados (most opened), Películas populares and
   Series (most recent by Xtream `added`).
9. **Historial** — implemented: last 15 opened items per section, exposed as
   a category in TV/Películas/Series/Deportes.
10. **EPG** — implemented with one declared simplification: XMLTV download
    (gzip-aware) from Xtream `xmltv.php` or the M3U EPG URL, parsed and stored
    **processed** (TSV + meta, not the raw ~37 MB XML), now/next lookup by
    tvg-id/epg_channel_id with normalized-name fallback and the Windows
    ambiguity rule. **The Guía tab is a per-channel now/next list, not a
    full time-grid — the grid is deferred** (allowed in the v1 scope).
11. **Buscar** — implemented: accent/case-insensitive search over loaded
    content, results grouped by type, D-pad navigable.
12. **Ajustes** — implemented: playlist management, content sync now, EPG
    last-update + update now, image-cache usage + clear (Glide disk cache
    hard-capped at 500 MB), about/version. No decorative settings shipped.
13. **Persistence** — implemented: favorites, history and playback progress
    in local JSON files, mirroring the Windows concepts.

## Known limitations

- Guía is a now/next list, not a time-grid (see item 10). Deferred by design.
- **No on-device testing yet.** First run on the Stick may surface Fire OS
  focus/codec quirks that only appear at runtime; the code was statically
  reviewed but never executed on hardware or an emulator.
- R8/minification is **off** for v1 (fewer surprises debugging a first build);
  the APK is 5.8 MiB and multidex (2 dex files), normal for Media3 + Glide
  at minSdk 24.
- The debug build type is also signed with the release key (deliberate, so
  sideloaded test builds share the same identity).
- Sports detection is the Windows name rule only ("sport"/"deporte" in the
  category name) — providers naming categories differently won't be picked up.
- EPG processed storage keeps the programme window starting at −6 h (Windows
  rule); there is no multi-day browse UI in v1.


## Addendum 2026-10-21 — Instalación en hardware real y hotfixes de navegación

La v1.0.0 se instaló en el Fire TV Stick 4K Plus del usuario (modelo AFTKM
«karat») por ADB desde la Raspberry Pi 3 de su LAN (la pi3 ya traía `adb`
34.0.5; la llave ADB de este VM se copió a su `~/.android/`, así que las
actualizaciones no piden nuevo aviso en la TV). La ruta ADB por túnel SSH
desde el VM se colgó tras la autorización; la pi3 por LAN es la vía buena.

Primera corrida real → fallo bloqueante reportado por el usuario («no entra a
ajustes»): la cabecera (8 pestañas + título «MiTV para Fire TV») no cabía;
«Buscar» se comprimía en vertical y «Ajustes» quedaba con ancho cero. Serie de
hotfixes, todos verificados con capturas reales del Stick:

- **v1.0.1** — cabecera sin título, pestañas compactas de una línea y barra
  dentro de un `HorizontalScrollView` que sigue al foco. Las 8 pestañas ya se
  ven (`v101-home-fixed.png`), pero el recorrido con el mando seguía fallando.
- **v1.0.2** — diagnóstico paso a paso: la búsqueda de foco de Fire OS se
  pierde tras los reemplazos de fragmento (pulsaciones → dejaban de mover el
  foco). Navegación ←/→ entre pestañas determinista en
  `MainActivity.onKeyDown` cuando el foco está en una pestaña. Probado:
  Inicio→TV→Películas→Series→Deportes→Guía, un paso por pulsación
  (`v102-step*.png`).
- **v1.0.3** — al pasar a Buscar el foco quedaba nulo y el sistema saltaba a
  la primera pestaña: si el foco es nulo, ←/→ lo restaura a la pestaña actual;
  y `SearchFragment` difiere `input.requestFocus()` con `root.post {}` (antes
  lo pedía en `onCreateView`, antes de que la vista existiera).
- **v1.0.4** — el input de Buscar ocupa todo el ancho y tragaba ←/→ con el
  cursor, dejando Ajustes inalcanzable desde ahí: ids generados en las
  pestañas (`tabId`/`focusTab` en MainActivity), `nextFocusUpId` del input a
  la pestaña Buscar, y saltos por los bordes del texto (→ al final →
  Ajustes; ← al principio → Guía). **Verificación final: Ajustes abierto en
  «Listas IPTV» (`v104-ajustes.png`).**

Estado: v1.0.4 instalada y navegable de punta a punta. Pendiente del usuario:
agregar su lista en Ajustes → Listas IPTV → + Agregar lista.

---

## Addendum 2026-10-21 (v1.0.5 / v1.0.6, installed on the Stick)

- **v1.0.5** (versionCode 6): tab order changed at the user's request — Buscar is now the LAST tab (Inicio, TV en vivo, Películas, Series, Deportes, Guía, Ajustes, Buscar); SearchFragment edge jumps updated (Left at text start → Ajustes).
- **v1.0.6** (versionCode 7): root cause of "can't reach Settings / jumps back to first tab" found via per-hop screenshots: SettingsFragment.render() ended with an unconditional `rootBox.getChildAt(1)?.requestFocus()` and runs on every onResume after `removeAllViews()`. Entering Ajustes with a playlist present destroyed the focused row during the rebuild, and Fire OS fell back to the window's first focusable view (the Inicio tab), whose focus listener selected Home. Fix: render() now preserves focus by row index and never steals focus when it is outside the list. Verified: full 7-press chain Inicio→…→Ajustes→Buscar lands correctly (v106-d6/d7 screenshots).
- Known cosmetic wart (not fixed, pending user OK): after a sync finishes, MainActivity's fragment rebuild can likewise drop a focused settings row and land the user on Inicio. One-line mitigation: rebuild with requestFocus=true in syncContent.
- The user added his own Xtream list ("mejortv", active) but sync has never succeeded: Settings shows 0/0/0, "Última: nunca". Captured toast error: "Expected URL scheme 'http' or 'https' but no scheme was found for http://..." — the request fails locally while building the URL. Server field looks clean in the editor; the playlist JSON lives in private storage (not readable, password masked). Panel answers HTTP 404 to dummy credentials from his LAN (may be normal API-hiding). Next step is the user's: retype the list fields (a stray character, most likely in the masked password, is the prime suspect), then Sincronizar ahora.

## Addendum 2026-10-21 (v1.0.7, installed on the Stick)

- Channel rows in LiveFragment rendered the channel name vertically, letter by letter: the name TextView had no width/weight inside a wrap-content row, and the fixed columns (categories 250dp + preview 430dp + num/star/logo) starved the info column to ~18dp. Fix (authorized by the user): name TextView now takes the full info-column width on one line (weight 1, maxLines 1, ellipsize END) and widths were rebalanced — categories 250→215dp, preview 430→390dp (video height 218→201dp, keeps 16:9), logo 54→48dp, number column 30→26dp. Verified on the Stick: names read horizontally with program title, progress bar and times (v107-live.png).
