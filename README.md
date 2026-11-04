# MiTV Fire TV

App nativa de TV para Fire TV Stick (Fire OS / Android TV), en Kotlin con
ExoPlayer/Media3, pensada para manejarse con el mando a distancia.

- **Listas**: Xtream (API) y M3U, con lista activa y editor en la app.
- **TV en vivo**: categorías en la barra lateral, vista previa automática en
  silencio, guía de ahora/siguiente por canal, favoritos con la tecla ☰.
- **Deportes**: las categorías de deporte aparecen también en su sección.
- **Películas y Series**: parrilla con pósters, ficha con sinopsis, continuar
  viendo, marcar como visto y favoritos. La tecla ☰ abre un menú de opciones
  (reproducir, favorito, visto).
- **Búsqueda** insensible a acentos, **Historial** (15 recientes) e **Inicio**
  con continuar viendo y lo más reciente de la lista.
- **EPG**: XMLTV de la lista (Xtream: `xmltv.php`), procesada a ahora/siguiente.

La v1 no trae parrilla de guía; la guía es lista de ahora/siguiente por canal.

## Compilar

Dos caminos, mismo resultado (versiones fijadas en los scripts de Gradle):

**Máquina normal, con internet** (el clon compila solo; no hace falta
`localrepo/`): requisitos JDK 21 y Android SDK (API 34).

```sh
./gradlew assembleRelease
```

**Entorno sin acceso a Maven** (la VM de compilación): el grafo Maven
completo se espeja una vez a `localrepo/` y Gradle compila contra ese
espejo, sin red.

```sh
python3 tools/mvnwalk.py   # genera localrepo/ (no se sube al repo)
./tools/build.sh :app:assembleRelease   # gradle --offline
```

El APK queda en `app/build/outputs/apk/release/app-release.apk`.

## Firma

La llave de firma (`keystore/`) **no está en este repositorio**. Sin la misma
llave, el Stick no acepta una instalación como actualización de la app ya
instalada: hay que generar una llave propia o usar la del autor.

Sin keystore, el build no falla: el APK release sale **sin firmar** (con un
aviso) y el build debug usa la llave debug automática. Las contraseñas de
la llave se leen de `keystore/KEYSTORE-INFO.txt` o de las variables de
entorno `MITV_STORE_PASSWORD` / `MITV_KEY_PASSWORD` (las variables ganan).

## Versiones

- v1.0.0 — primera versión: listas, TV en vivo, películas/series, guía.
- v1.0.1–v1.0.4 — barra de pestañas, navegación con el mando y foco del buscador.
- v1.0.5 — «Buscar» pasa a ser la última pestaña.
- v1.0.6 — el panel de Ajustes ya no roba el foco al entrar.
- v1.0.7 — el nombre del canal se dibuja en horizontal en TV en vivo.
- v1.0.8 — los pósters de Películas/Series/Inicio ya no quedan tapados y la
  tecla ☰ abre un menú de opciones de verdad.

El detalle de cada versión está en `BUILD-REPORT.md`.

## Licencia

MIT — ver `LICENSE`.
