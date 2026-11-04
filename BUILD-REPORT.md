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

## Addendum 2026-10-27 (v1.0.8, installed on the Stick)

- Posters in the Movies/Series/Home grids were black: the card ImageViews used
  the opaque `card_bg` shape as `foreground`, painted OVER every loaded image.
  Fix: new border-only foreground `card_fg.xml` (transparent fill, focus frame
  only) in all 5 tile spots (VodFragment movie + series adapters, HomeFragment
  media/poster/channel cards). Verified on the Stick: posters and channel
  logos now show (v108-r1/r3/r4.png).
- The remote's Menu (☰) key now opens a real options menu instead of silently
  toggling a favorite (and doing nothing on the detail screen): new
  `Ui.showMenuDialog` (dark panel, DPAD-friendly). Movie cards/detail: Play /
  Continue, Favorite, Mark as watched. Series cards: View episodes, Favorite.
  Series detail: Favorite. Movies/Series hint now reads "☰ Menú Opciones".
  Verified: menu opens focused on the first option (v108-r2.png).
- Source published to GitHub (public, sanitized): panchocespedes2016/MiTV-FireTV
  (commit 86ee63f). The signing keystore is NOT in the repo.

## Addendum 2026-10-27 (v1.0.9, installed on the Stick)

- Navigation bug: pressing Down from the TV tab bounced back to Inicio.
  Cause: a category row's focus listener called applyCategory(), which ran a
  full notifyDataSetChanged() on the category list and swapped the channel
  adapter; the focused row was detached, focus was lost, and Fire OS fell
  back to the first tab (Inicio). Fix in LiveFragment and VodFragment
  (same pattern): applyCategory() now early-returns when the category is
  already selected and refreshes only the previously/newly selected rows
  via notifyItemChanged, so the focused view stays attached. selectedCat
  starts empty so the initial load still runs. Verified on the Stick with
  the exact reported path: Right (TV tab) -> Down (Historial) -> Down x2
  (Todo) -> Right (channel 2, preview playing) -> Down (channel 3)
  (v109-u2/u4/u5/u6.png).

## Addendum 2026-10-31 (v1.0.10, installed on the Stick)

- Screensaver/sleep cut playback: Fire TV idle timers fired while video
  played because nothing held the screen on. Fix: PlayerActivity holds
  FLAG_KEEP_SCREEN_ON while playing (released on pause/destroy) and the
  Live preview PlayerView sets keepScreenOn. Installed 2026-10-31
  (versionCode 11). The long idle proof (screensaver not appearing after
  5+ minutes of playback) was never confirmed on screen by the agent.

## Addendum 2026-11-04 (v1.0.11, installed on the Stick)

- Fast scrolling down the channel lists jumped back to Inicio. Cause: the
  focused row was recycled before its replacement was laid out, focus was
  lost, and the system's fallback search landed on the first tab (Inicio),
  whose focus listener switches the whole screen. The lost-focus recovery
  in MainActivity only covered Left/Right. Fix, two layers: (1) new
  KeepFocusLinearLayoutManager / KeepFocusGridLayoutManager (Ui.kt) whose
  onFocusSearchFailed focuses the edge visible child instead of letting
  the search escape the list; applied to every scrolling list (Live
  categories/channels, Vod categories/grid, Guide, episode list);
  (2) MainActivity remembers the last content-focused view and, on any
  DPAD direction with focus lost, restores it (or the current tab when it
  is gone), never while a menu dialog owns the focus (Ui.dialogShowing).
  Verified on the Stick: normal path tab -> categories -> channels with
  preview playing, 40 fast Downs in channels, fast Downs in categories
  (Live and Movies), grid entry and fast Downs in the grid, Up back to
  the tab row — no jump to Inicio in any run (v111-w2/w3/w4/x1/z1/z2).
  Known edge, observed: on the fastest scrolling the focus can still be
  lost when the focused row is recycled; the restore then falls back to
  the tab and scrolling resumes from the top of the category list instead
  of the previous position. A position-aware restore is the follow-up.

## Addendum 2026-11-04 (v1.0.12, installed on the Stick)

- Position-aware focus restore (follow-up to the v1.0.11 edge). New
  FocusRestorable interface (MainActivity.kt): when MainActivity's
  lost-focus recovery runs with no focused view, it first asks the current
  fragment to restore focus to its last recorded adapter position
  (scrollToPosition + posted requestFocus). LiveFragment tracks the last
  focused category/channel position (channels preferred, categories as
  fallback); VodFragment tracks category/grid position (grid preferred);
  GuideFragment tracks its row position. The v1.0.11 view/tab fallback
  stays as the last resort. Installed 2026-11-04 (versionCode 13,
  APK md5 399a8d41a889d0c59ffaf8eb5609220e).
- Verification on the Stick, honest result: the restore is installed, but
  the reported symptom PERSISTS. Entry into channels and grid confirmed
  on screen (v112-r1: channel 1 focused, preview playing; v112-r3:
  "Hoppers" card focused), yet after 40 fast Downs in channels the focus
  ended on the TELEMUNDO category (v112-r2, byte-identical end state to
  v1.0.11) and after 30 fast Downs in the grid it ended on the ESTRENOS
  2026 category (v112-r4). A slow control run (16 Downs, 1 s apart,
  v112-s2) escaped the same way, so speed/recycling is NOT the door.
- Root cause found in the RecyclerView 1.3.2 source: when
  onFocusSearchFailed returns the already-focused view (or any view not
  strictly further in the direction), isPreferredNextFocus vetoes it and
  focusSearch falls through to the parent, whose window-wide geometric
  search jumps to the sibling list. The escape is the focus SEARCH, not
  focus loss, so a restore that only fires when focus is null never
  engages. The fix for that door (proposed as v1.0.13, not built):
  a RecyclerView subclass that traps Up/Down escapes into a sibling
  RecyclerView while still allowing Left/Right between lists and Up to
  the tab bar (the tab bar is not a RecyclerView). Awaiting the user's
  decision; v1.0.12 stays installed meanwhile.

## Addendum 2026-11-04 (v1.0.13, installed on the Stick)

- The v1.0.11 manager was the bug. The user reported live, precisely:
  category -> right into channels -> Down jumps to the category list the
  moment the first off-screen channel must come in. Confirmed against the
  RecyclerView 1.3.2 bytecode: the stock onFocusSearchFailed lays out
  (fill) and returns the next row, which is what moves focus past the
  last visible row; the v1.0.11 override replaced it with the edge
  visible child, which at a scroll boundary IS the focused row, and
  isPreferredNextFocus vetoes a same-row answer, so the search fell
  through to the parent and jumped to the sibling list. Fix, two
  layers: (1) KeepFocus managers delegate to the stock
  onFocusSearchFailed FIRST and only fall back to the edge child at the
  true list edge; (2) new FocusTrapRecyclerView (Ui.kt): Up/Down results
  landing inside a sibling RecyclerView keep the current focus, while
  Left/Right between lists and Up to the tab bar still work (the tab
  bar is not a RecyclerView). Applied to all six lists (Live
  categories/channels, Vod categories/grid, Guide, episode list).
  The v1.0.12 position restore stays as the total-focus-loss layer.
  Installed 2026-11-04 (versionCode 14, APK md5
  047b63878e42c74de8c8804263ba5926).
- Verification on the Stick: grid leg VERIFIED (v113-t4/t5: after 10 and
  30 Downs the focus stayed on grid cards in scrolled rows, no escape
  to categories, no jump to Inicio). Live-channels leg NOT verified:
  the script never entered Live TV (shots show the Home screen), so no
  claim is made for channels; the user's own live test with the remote
  is the pending ground truth.

## Addendum 2026-11-04 (v1.0.14, installed on the Stick)

- Authorized by the user 2026-11-04 12:10 EST after a code-only
  comparison (no code touched) of Live/Guide vs Vod adapters, plus
  research into the Lumora player (native Kotlin, Views/XML, Media3,
  Xtream, Fire TV remote): holder-owned views, ListAdapter.submitList
  with background diffing, explicit focus exits wired by ID, explicit
  focus restore. Scope: (1) holder-owned rows with zero view creation
  at bind in TV/Deportes/Guia (mirror the Vod pattern); (2) list
  changes by diff, not a new adapter per category; (3) focus exits
  wired by name (top-row Up -> its tab); (4) keep the existing restore
  layers; (5) NEW, user requirement: BACK inside any column goes to
  its parent (category or menu/tab). Movies/Series rows were already
  standard, so changes there were limited to the adapter migration +
  BACK handling, per his "no toques nada ahi" order. Channel list
  stays one column (his "columnas" was a slip of his own).
- Files: LiveFragment, VodFragment, GuideFragment, HomeFragment,
  SearchFragment, VodDetailActivity, MainActivity (BackToParentHandler
  + isDescendantOf), Ui.kt (machinery unchanged), versionCode 15 /
  versionName 1.0.14.
- Verification: FIRST install caught the agent's own bug — the
  ListAdapter migration never submitted the categories list
  (catAdapter created and set, submitList never called) so the
  categories column was empty. Fixed (submitList in onCreate, Live
  and Vod), rebuilt, reinstalled (clean APK md5
  19ab5d9d48797696c360fc7b55069202, verified by dumpsys 15/1.0.14).
  Second run: entry OK (focus on channel 437, preview populated),
  but after 40 fast Downs the escape to Inicio PERSISTED (Home
  screen, Jujutsu Kaisen card focused). BACK card->tab verified on
  Home. Movies/Guide BACK legs INVALID: a second BACK on the Home
  tab exited the app to the Fire TV launcher, so the rest of that
  script navigated the launcher, not MiTV. BACK on the tab bar at
  Inicio exits the app (default Android behavior) — observed twice,
  flagged to the user.
- Diagnosis, proven by live focus logging (the Stick records no
  Debug lines device-wide; Log.i + live-streamed logcat required; the
  256 KiB main buffer rotates fast): every Down search WORKED
  (436->437->438->439->440, one via the stock manager at a scroll
  boundary), then 34 ms after the last Down, with NO key pressed and
  no onFocusSearchFailed/trap/restore line, focus went null and the
  framework fallback landed on the first focusable view, the Inicio
  tab, whose focus listener switched the screen. The escape is a
  detach-driven focus loss between keys, not a search failure; the
  search layers never run. The bind-churn theory is dead as the sole
  cause: holder-owned rows + diff updates did not change the symptom.
  A temporary instrumented build (md5 7cb33b90...) was left on the
  Stick during diagnosis; it was replaced by the clean 1.0.15 build.
  Instrumentation was removed from the source before committing.

## Addendum 2026-11-04 (v1.0.15, installed on the Stick)

- Fix for the proven mechanism, in MainActivity only: a tab switches
  screens ONLY when its focus gain is explicit. A tabFocusExpected
  flag is set before every explicit tab focus request (focusTab,
  selectTab with requestFocus, Left/Right tab moves, tab clicks,
  onCreate home focus, null-focus restore); the tab focus listener
  consumes it. A gain without the flag is a fallback gain: the
  screen does NOT switch; focus goes back to the content that lost
  it (last focused content view if still attached, else the
  fragment's FocusRestorable restore, else the current tab keeps
  focus). Gains while a dialog is showing are left alone. Audit:
  every tabViews requestFocus in the app goes through a flag-setting
  path; all other requestFocus calls target content views.
  versionCode 16 / versionName 1.0.15, clean APK md5
  23c379d8c698ad2a0fc93115bc37eed0 (6,0 MB range), verified by
  dumpsys 16/1.0.15.
- Verification on the Stick (v115 runs, all 12 captures read): entry
  OK (focus on channel 437, preview with EPG + video). After 40 fast
  Downs the focus STAYED in Live TV on channel 477 (437+40 exactly),
  preview playing video — THE ESCAPE IS FIXED at ADB speed. BACK
  legs all verified: channels->categories (Todo focused),
  categories->tab (TV en vivo focused), movies grid->categories
  (Historial), categories->tab (Peliculas), guide list->tab (Guia),
  Home card->tab (Inicio). Left/Right tab switching intact (4x Left
  from Guia landed on the TV en vivo tab with the Live screen).
  Caveats: (1) the first v115 run's entry failed on a 3 s cold-start
  wait after a fresh install (keys navigated Home); with a 6 s wait
  one Right enters Live TV — the entry script needs the longer wait
  on fresh installs. (2) In one mid-session capture (v115-b1) the
  channel rows showed "Sin informacion" with empty bars; the very
  next fresh entry (v115-e1) showed EPG normally, so it was
  transient and was not investigated; flagged here for the record.
  (3) ADB keys are slower than remote repeat and can be lost under
  preview load: the user's own remote test remains the ground truth.
- Search BACK mapping (judgment call, flagged to the user in the
  1.0.14 report): results go to the tab/menu, not back to the input.
- Commits: 44f7c32 (v1.0.14, instrumentation removed) and this
  version. Both pushed to GitHub with this addendum.
