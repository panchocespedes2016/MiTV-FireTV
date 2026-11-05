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

## Addendum 2026-11-04 (v1.0.16, installed on the Stick)

- User report on v1.0.15, live with the remote: no more jumps to
  Inicio and the list holds more channels, but fast scrolling usually
  lands the channel column inside the FIRST category (generally
  Historial) around channel 20; slow scrolling is fine. Diagnosis
  from code (nothing touched before approval): the category row's
  focus listener switches content on ANY focus gain and wipes the
  channel position, with no distinction between user navigation and
  a fallback/restore gain. v1.0.15 only blocked fallback gains
  landing on the tab bar, not gains landing in the category list.
  Chain: fast scroll detaches the focused row between keys (proven
  mechanism, v1.0.14 logs); the fallback lands in the category list;
  the first row (Historial) gains focus and applyCategory swaps the
  content; the next Down puts focus back in the channels, now
  showing Historial. The same pattern exists in VodFragment.
- Fix, MainActivity untouched: (1) suppressCategoryApply flag in
  LiveFragment and VodFragment — restoreFocus()/focusCatRow() focus
  category rows through requestCategoryFocus(), and a gain that
  arrives while the flag is set is consumed, never a selection (the
  flag is also cleared by a backstop post); (2) holders keep their
  bound item, so listeners no longer resolve the adapter position,
  which can be NO_POSITION while a diff/layout is settling; (3) the
  list containers themselves (catList, chanList, grid, guideList)
  are isFocusable=false — only rows take focus, so a fallback can
  never park on a container. Category switching by real navigation
  (keys/click) is unchanged. GuideFragment has no categories, only
  the container change. versionCode 17 / versionName 1.0.16, clean
  APK md5 b0d44788e9416ef0161d4bbd6863ae5d, verified by dumpsys
  17/1.0.16.
- Verification on the Stick (v116 runs, all captures read): entry OK
  (channel 437, Todo, preview + EPG). After 40 fast Downs the focus
  STAYED in Todo on channel 477 with the preview playing — no
  Historial switch. BACK channels->categories kept Todo content
  (guard holds on the restore path). Real navigation still
  switches: Up to Favoritos showed the 12 favorite channels, Down
  back to Todo restored the 1537 list. BACK categories->tab OK.
  Movies: BACK from the grid to the Historial row kept the Todo
  grid (guard holds in Vod too); BACK to the tab OK. Caveats:
  (1) captures 1 s after a 12->1537 category switch showed a
  transient mixed state (stale favorite rows + one Todo row); a
  settle probe with 2 s waits + re-entering the list rendered Todo
  correctly, so it is a mid-diff settle artifact, not a stuck
  state. (2) The non-contiguous top holder (row "6" above 437)
  seen in these runs also appeared in the v1.0.14 verified runs;
  pre-existing cosmetic quirk, out of scope, left alone. (3) ADB
  keys are slower than remote repeat: the user's own remote test
  remains the ground truth.

## Addendum 2026-11-04 (portability, no app version change)

- External review (another AI, static only) claimed the published tree
  could not be rebuilt. Verified against the real tree: TRUE for a
  clean clone — app_icon.png/banner.png existed locally but
  `.gitignore` (`*.png`) kept them out of git; settings declared
  only the localrepo mirror; signing required the untracked
  keystore, and debug builds used the release key. Local builds
  always worked; the published repo did not.
- Fix (commit 985fa2e, pushed; NO behavior change, NO reinstall —
  the Stick stays on v1.0.16): (1) `.gitignore` `*.png` -> `/*.png`
  (root captures only), artwork PNGs now tracked; (2)
  settings.gradle.kts keeps localrepo FIRST and adds
  gradlePluginPortal()/google()/mavenCentral() as fallbacks, so
  `./gradlew` on a machine with internet resolves the same pinned
  versions; (3) app/build.gradle.kts: release signing applies only
  when the keystore file AND both passwords exist (file or
  MITV_STORE_PASSWORD/MITV_KEY_PASSWORD env vars), otherwise the
  release APK builds UNSIGNED with a configuration warning;
  debug builds use the default debug keystore, never the release
  key; (4) tools/build.sh keeps the VM offline default (pinned
  JDK/Gradle + --offline) with MITV_GRADLE/MITV_ONLINE/JAVA_HOME/
  GRADLE_HOME overrides, and README documents both build paths.
  The keystore password file stays gitignored in the workspace +
  the user's PC backup (unchanged convention).
- Verified: git check-ignore (artwork not ignored, root captures
  still ignored); every pinned coordinate returns 200 on its
  fallback host (AndroidX on Google's repo, glide/okhttp/coroutines
  on Maven Central, AGP/Kotlin plugin markers on the plugin
  portal); debug build WITHOUT the keystore (moved aside, restored
  with md5 verified) SUCCEEDED with the warning; release build
  with the keystore restored SUCCEEDED, same cert SHA-256
  a9b98715... and version 1.0.16/17. The online-resolution path
  itself (./gradlew on a machine with internet) could NOT be
  tested from this VM — stated, not assumed.

## Addendum 2026-11-04 (v1.0.17, installed on the Stick)

- Second half of the external review ("y lo otro"): the user approved
  groups A (verified safe fixes) and B (fluency), C deferred. 17 files
  changed, versionCode 18 / versionName 1.0.17, clean APK md5
  fa9d16462b16fe7696795e05d39aab99, verified by dumpsys 18/1.0.17,
  same release cert. A service restart killed the first foreground
  build mid-run and left the stale v1.0.16 APK in place; the build
  was re-run detached and the APK timestamp/version re-verified
  before install.
- Group A: (5) SettingsFragment/VodDetailActivity/MainActivity
  dropped their never-cancelled CoroutineScope for lifecycleScope;
  SettingsFragment.render() guards isAdded. (6) VodDetailActivity
  resolves the item from ContentStore by an Intent "key" extra,
  VodDetailRegistry kept only as fallback. (7) Net.escPath() encodes
  Xtream path segments (URLEncoder, + -> %20); query params keep
  Net.esc. (8) Net.getJsonArray() throws on an explicit panel denial
  (auth != 1) or an error message field; PlaylistLoader.checkAuth()
  validates the bare player_api.php call first; PlaylistEditActivity
  validates Xtream/M3U/EPG URLs via normalizeServer() (scheme
  case-insensitive, added when missing, host required). (9) save()
  validates all new values in locals before mutating the stored
  Playlist. (10) EpgStore.clear() (memory + TSV + meta, updatedUtc=0)
  runs when the active list is marked or deleted; meta files now
  persist the ambiguity set (!AMBIG! line). (12) PlaylistLoader
  single-flight refresh (AtomicBoolean; concurrent call returns
  "Ya hay una sincronización en curso"). (13) PlayerActivity
  pauses in onStop and resumes in onStart if it was playing;
  setHandleAudioBecomingNoisy(true). (14) Track dialog clears
  overrides of the type before adding/replacing. (16) Playback
  section recorded via sectionFor() (sports categories -> "sports"),
  so Guide/Home/Search plays land in Deportes history. (18)
  Favorite MENU toggle in Favoritos re-applies the category with
  force=true, so the row disappears at once. (19a) updateProgress
  no longer bumps updatedUtc. (20) Util.fold() uses one compiled
  regex. (25) Cache size measured on IO, re-render only on change
  ("Calculando…" first). (32) AlertDialog theme dark (both
  android: and appcompat attributes, panel background). (33) Dead
  code removed: Util.utcHm, Ui.hintButton, Ui.spinner, the
  sportsOnly dead expression, an unused val in VodFragment.
- Group B: (21) SearchFragment builds folded-name indexes and
  filters sequences with take(30/40/40), debounced 200 ms. (22)
  HistoryStore writes via a single-thread executor, coalesced,
  snapshots under the lock, tmp+rename.
- Deferred (Group C, with reasons given to the user): fragment
  retention across tab switches (15), tv_archive_duration units
  (17), startup/EPG-refresh behavior (23/26), streaming parsers
  (24), keystore password file location (27), cleartext narrowing
  (28, impossible for arbitrary user URLs), credential encryption
  (29, accepted personal-use risk), tests/CI (30), strings.xml
  (31), M3U classification/decoding/Glide fallbacks (19c-e).
- Verification on the Stick, phase 1 + phase 2 (captures v117-*,
  all read): burst guard HOLDS (spaced 10 Downs: 6->15, one ADB key
  lost under preview load, no escape; unspaced 40 Downs: 6->15,
  heavy key loss, no escape; BACK channels->categories keeps Todo).
  Dark dialog VERIFIED (playlist dialog, b0). Favorite ADD/REMOVE
  VERIFIED on screen: MENU added ch 443 (Favoritos 12->13, toast,
  star filled), MENU in Favoritos removed it at once (row gone,
  count back to 12, toast "Quitado de Favoritos", focus on the next
  row) — the user's favorites were restored to their original 12.
  Search VERIFIED: tapping the input opens the Fire OS keyboard,
  "cnn" returned "Canales (5)" behind it. EPG mid-task switch
  VERIFIED: OK on "Actualizar guía ahora" + BACK at once -> Inicio
  alive, no crash dialog, pid 2160, no FATAL in logcat; the EPG
  timestamp advanced 3:46 -> 3:57 p.m. (the blocking download
  completes even though the scope is cancelled). Cache size
  VERIFIED: "En uso: 192 MB". Player: fullscreen playback VERIFIED
  (i5); pause-on-Home is CODE-VERIFIED ONLY (onStop->pause) — the
  relaunch after HOME showed the list with the preview, not the
  player, so the resume path is untested by ADB; the user's ears
  are ground truth. Code-level only (no second list / bad
  credentials used — never with the user's credentials): auth
  validation, single-flight, EPG clear, URL validation, path
  encoding, sports-section history.
- Honest misses during verification, all re-run: phase 1's
  settings->search navigation never left Ajustes (UPs lost), so the
  EPG and search legs were re-run in phase 2; one phase-2c leg
  typed "3" into the search box because the Fire OS keyboard was
  still open when the Ajustes tab was tapped (DPAD keys went to the
  keyboard) — the leg was re-run with the keyboard dismissed first.
  The Stick was left on Inicio, app alive, favorites at 12.
- Commit + push pending in this same pass (see git log).

## v1.0.18 (2026-11-04, versionCode 19) — focus holds, list known at once

User's diagnosis, confirmed in code: fast Down scrolling detached the
focused row between keys, focus fell back to the Inicio tab, the
v1.0.15 guard blocked the screen switch but the restore sometimes did
not land (row not laid out yet), the next Down searched from the tab
and dropped the focus on the first category (Historial), and the cycle
repeated. His rule: if the next row cannot be focused — end of list or
row not ready — focus STAYS where it is. And his question: the whole
list must be known the moment a category is focused, not settled later.
- Synchronous lists: the five ListAdapters (Live Cat/Channel, Vod
  Cat/Movie/Series) became plain RecyclerView.Adapters with setItems()
  (swap + notifyDataSetChanged in one call). The filtered data was
  always computed synchronously; the async background diff was what
  made the list feel "made on the fly" (stale/mixed rows ~1s after a
  switch in v1.0.16). Category switches run while focus is in the
  sidebar, so the full refresh cannot detach the focused row. The one
  path that refreshes the list under the focused row (unfavoriting
  inside Favoritos) re-focuses its row explicitly right after, and the
  Vod movie handler guards notifyItemChanged(p) to a valid position.
  Diff callbacks and ListAdapter imports removed. GuideAdapter was
  already a plain adapter (fixed list); untouched except restore.
- FocusTrapRecyclerView: for Up/Down, ANY answer outside the list now
  keeps the current focus (before: only answers inside a sibling
  RecyclerView were trapped; the tab bar is not one, so the edge
  escape reached it). All legitimate Up exits (top row -> its tab)
  are row key listeners, verified in every list. Left/Right still
  escape. containVerticalFocus=false for the episode list in
  VodDetailActivity (Up must reach the buttons above it).
- MainActivity: new fallbackTabGain flag — set on a fallback tab
  gain, cleared when focus is anywhere explicit. While set, an Up/Down
  is treated like the lost-focus case (restore content, consume the
  key) instead of searching from the tab onto the first category.
  Left/Right on a parked tab still move between tabs.
- restoreFocus() in Live/Vod/Guide: bounded retry (up to 3 posted
  passes) so a row that is not laid out yet still gets the focus.
- Verification on the Stick (captures v118-m0..m7, n0/n1, p0/p1,
  q0..q4, final — all read): spaced 10 Downs 1->11 exactly, no
  escape; unspaced 40 Downs 11->15 (heavy ADB key loss, known
  artifact), no escape, no category change; BACK keeps content.
  Category switch FULLY swapped 0.3s after the gain (Favoritos rows,
  all starred, no stale rows) and identical at 2.3s — the sync-list
  claim verified on screen. Favorite ADD (MENU on Unitel, 12->13,
  toast, filled star) and REMOVE inside Favoritos (row gone at once,
  count back to 12, toast, focus on the next row, preview playing)
  verified; favorites restored to the original 12. Grid: 20 fast
  Downs stayed in the Peliculas grid, BACK grid->categories holds.
  Top-edge: extra UPs past row 1 parked the focus on the TV tab
  (by design) with no cycle. No FATAL in logcat, app alive
  (pid 13615 after the fresh install). The Stick was left on
  Peliculas, app alive, favorites at 12.
- Code-level only: the fallbackTabGain/parked-tab key path (cannot be
  produced deterministically by ADB — the race needs remote repeat
  speed); the user's remote is ground truth for the cycle being gone.
- APK: clean md5 b60806ef3d3f9249d4c45b68041454f1, 6,045,531 bytes,
  same release cert (SHA-256 a9b98715...). Installed via pi3 ADB,
  dumpsys confirms 19/1.0.18. Build 3m 23s (detached; the earlier
  foreground build log is /tmp/b118.log).

## v1.0.19 (2026-11-04, versionCode 20) — channel numbers fit 5 digits

User's report: the channel-number column only fit 3 digits. The num
TextView in LiveFragment's ChannelAdapter was a fixed 26dp at 15sp;
a digit is ~8.3dp, so 4 digits (~33dp) already overflowed it. Widened
to 56dp (~42dp for five digits + margin); the name column (weight 1)
absorbs the 30dp. Only place with channel numbers — Search/Guide/Home
show none, and Deportes uses the same fragment. Verification on the
Stick (captures v119-w1/w2, both read): rows 1-5 and 7-11 render with
numbers, star, logo and name aligned, no overlap, no clipping; preview
playing. Honest limit: no 5-digit channel exists in the current list
(max 1543), so a 5-digit number itself cannot be screenshotted — the
fit follows from the width arithmetic, and 2-digit numbers already sit
in the column with room to spare. APK: clean md5 9eb934e1cde15cf9f660c2a8ebb79d0f,
6,045,543 bytes, same release cert; installed via pi3 ADB, dumpsys
confirms 20/1.0.19. Build 3m 20s (detached, /tmp/b119.log). Favorites
still 12; Stick left on TV en vivo.

## v1.0.20 (2026-11-04, versionCode 21) — categories column in the Guide

User's request: the Guide gets the category column with exactly TV's
dimensions, plus channel numbers and the favorite star. GuideFragment
rewritten on LiveFragment's layout: horizontal root, category column
215dp + 14dp marginEnd, panel background, 8dp padding, same 16sp
card_bg rows (Historial/Favoritos/Todo/groups, counts scoped to the
408 channels that have EPG); guide list weight 1. Rows: 56dp number
(room for 5 digits, as in v1.0.19), 30dp star, 54dp logo, now/next.
Category focus applies the filter synchronously (setItems, v1.0.18
pattern); position-aware restoreFocus with retry; BACK climbs
rows -> categories -> tab; row Menu toggles the favorite exactly like
TV (star flips at once, sidebar counts refresh, unfavoriting inside
Favoritos drops the row now). Verification on the Stick (captures
v120-g1/g2/g3/h1-h4, all read): Historial 11 / Favoritos 7 / Todo
408; Favoritos shows only starred rows with numbers restarting at 1;
Menu add (7->8, toast, gold star) then Menu remove (8->7, toast,
empty star) leaves favorites exactly as found; both BACK legs land
where they should; no escapes, no overlap. APK: clean md5
76c2bff366e9a48e86bee08132feae36, 6,047,871 bytes, same release cert;
installed via pi3 ADB, dumpsys confirms 21/1.0.20. Build 3m 27s
(detached, /tmp/b120.log). Note: the guide's Historial order follows
the same HistoryStore.recent("tv") call as TV's; the first rows
differed from TV's between captures because history only changes on
real plays (Playback.recordPlay), so intervening remote plays reorder
it legitimately. Stick left on the Guide tab.

## v1.0.21 (2026-11-04, versionCode 22) — Guide shows ALL channels

User's correction ("negativo"): the Guide must list every channel,
not only the ones with EPG. GuideFragment now builds one row per
channel (1543); a row without current EPG data shows "sin EPG" in
place of the programme lines, and rows with data keep AHORA/DESPUÉS
unchanged. The EPG-only filter and both EPG empty states are gone;
the only empty state left is no channels at all (no list / not
synced). Sidebar counts are now the full ones, identical to TV's
(Historial 15 / Favoritos 12 / Todo 1543 / groups). APK: clean md5
6df44dfdad575aafd26c76c14f8e38eb, 6,047,747 bytes, same release cert;
installed via pi3 ADB, dumpsys confirms 22/1.0.21. Build 5m 57s
(detached, /tmp/b121.log — Kotlin daemon failed to connect, fell
back to compiling without it). NOT verified on screen: the
verification run found the user live on the TV (Fire OS home in the
captures), so no ADB navigation was run; on-screen confirmation is
pending his own remote check.

## v1.0.22 (2026-11-05, versionCode 23) — Marquee on truncated text

User's request: any text that does not fit must scroll right-to-left,
hold 5 s, come back, and repeat forever. New `Marquee.kt`: a custom
animator (not the platform marquee, which jumps back and has a fixed
short pause). Cycle: scroll left at 40dp/s until the last letter is
visible, hold 5 s, scroll back to the start, hold 2 s so the beginning
stays readable, repeat. It runs ONLY on the focused row/card — the
unfocused rows keep their "…" — and text that fits never moves.
Wired everywhere text truncates: TV/Deportes rows (name + programme
line + preview title), Guide rows (name + AHORA + DESPUÉS), movie and
series cards (title + subtitle), Home cards (title + subtitle), Search
result rows (title + subtitle). Recycled rows stop the marquee on
bind and restart it if the row still has focus; losing focus stops it
and restores the ellipsis, scroll position and gravity.
Two bugs found by the on-screen verification before shipping: (1) the
first build measured the layout's line width, but with ellipsize END
that width always looks like the text fits, so nothing scrolled —
now measured with the paint; (2) a single-line TextView lays its text
out only as wide as the view, so scrolling just slid the truncated
text out and showed blank — the view is now set horizontally
scrolling while its marquee runs, so the full text is laid out and
the scroll reveals it. APK: clean md5 b8e19445f837abc33e3f0d4b72111aed,
6,052,859 bytes, same release cert; installed via pi3 ADB, dumpsys
confirms 23/1.0.22. Builds: /tmp/b122.log (first attempt, failed on a
stray brace + `hasFocus` called as a property), /tmp/b122c.log
(BUILD SUCCESSFUL 3m 02s, measurement bug), /tmp/b122d.log (6m 12s,
layout-width bug), /tmp/b122f.log (BUILD SUCCESSFUL 2m 46s, final).
Verified on screen (captures v122-p0–p3): TV row 2 name goes
"Adults: Venus" -> "lts: Venus" -> "Adults: Ve" -> "Adults: Ve" ->
"dults: Ven" across two cycles, programme line "Hace que mi culo se
oscile" likewise; unfocused rows never move. Guide verified too
(v122-r0/r1): the long row-5 name scrolls left and returns. Vod/Home/
Search use the same helper and wiring but were not screenshotted
individually. Note: the list has grown to 1565 channels (was 1543).
