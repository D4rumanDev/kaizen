---
title: Kaizen Architecture Map
type: architecture
tags:
  - architecture
  - modules
  - data-flow
updated: 2026-11-14
---

# Mapa Arquitectónico de Kaizen

Kaizen es una aplicación de Android TV (probada en un tvbox) para ver anime de varias webs sin depender de ninguna: cada serie se identifica por su **anime de AniList**, y de ahí salen la ficha, las uniones entre fuentes, las recomendaciones y el seguimiento de lo visto. Este mapa dice qué hay y cómo se conecta; lo que se hizo día a día está en [log.md](log.md) y el detalle del emparejado con AniList en [docs/wiki/emparejado-anilist.md](emparejado-anilist.md).

---

## 1. Módulos

| Módulo | Qué es |
| --- | --- |
| `:app` | Todo: interfaz de TV (Compose), reproductor, fuentes, catálogo, emparejado con AniList, sincronización multi-dispositivo WebDAV, bases de datos y tareas en segundo plano. Es el único módulo que se compila (`settings.gradle`). |

---

## 2. De la web a la pantalla

```text
fuentes por fichero (assets/sources/*.json, firmadas con ECDSA)
        │  ConfigurableSource: un motor, los selectores vienen del JSON
        ▼
HostGate ── turno por servidor, pausas si no responde, DNS de respaldo, verificaciones de Cloudflare (SourceChallenge)
        ▼
Catálogo (CatalogBuilder, cada 14 días / CatalogSeed portable) ── fichas (fuente, slug) ──► Ficha(user-db): id de serie + enlace con AniList
        ▼                                                                                 ▲
WebFicha (lo que dice cada web) ──► AnilistMatcher / AnilistSync ─────────────────────────┘ (por nombre, año, episodios, formato e idMal)
        ▼
AnimeFicha (lo de AniList, catalog-db) + TitleRules (uniones y portadas del usuario)
        ▼
Series unidas → catálogo, búsqueda, recomendaciones, ficha, «Continuar viendo»
        ▼
Reproducción: servidores (videoservers) → KaizenProxy (cabeceras) → ExoPlayer (PlaybackController + AFR + AniSkip)
```

Las uniones de series salen de que dos fichas compartan anime de AniList (más lo que el usuario une o separa a mano en `titles.json`); no se une por parecido de nombre.

---

## 3. Subsistemas de `:app`

### A. Fuentes (`app.kaizen.source`, `source.spec`)

- **Fuentes por fichero**: `assets/sources/*.json` (animeav1, animeflv, animeflvar, animegratis, jkanime, monoschinos, verani) con un `index.json` y su firma ECDSA P-256 (`SpecBundle`, `SpecSources.PUBLIC_KEY`). `ConfigurableSource` es el único motor; `SourceSpec` describe búsqueda, listados, ficha, capítulos y servidores. `RepoConfig` gestiona las direcciones y claves de los repositorios satélite.
- **Peticiones** (`SourceHttp`, `commons/HostGate`): una a la vez por servidor y por turno; tras varios fallos de red seguidos el servidor entra en pausa (2, 5, 10 min y 1 h) y se avisa; los `403` de bloqueo cuentan, los de verificación no. `FallbackDns` usa DNS sobre HTTPS (1.1.1.1) solo si el de la red no consigue conectar. `TlsPolicy` valida TLS con normalidad.
- **Verificación de seguridad de una web** (`SourceChallenge`, `bypass/`): si una fuente pide verificación (`403` con `challenge`), la app no la resuelve sola: lo anota y delega en el usuario mediante una ventana web; guarda las cookies y el User-Agent de esa sesión para las siguientes peticiones.
- **Cachés**: `SourceCache` (fichas), `ApiCache`, `CoverStore` y `BestCovers` (portadas), `MemoryCache`, `SingleFlight` (una petición idéntica concurrente).

### B. Catálogo y series (`source`)

- **Catálogo interno** (`CatalogBuilder` escribe, `Catalog` lee): recorre cada fuente como mucho una vez cada 14 días (o al activar una nueva) y guarda nombres y datos básicos; el directorio y la búsqueda salen de la base de datos, sin pedir páginas a los servidores. Una fuente caída no borra lo que había.
- **Catálogo unificado portable (`CatalogSeed`)**: descarga, verificación criptográfica e importación de catálogos generados con `tools/make-catalog-seed.py` (`catalogo-unificado.json.gz`). Neutral, agrupado por id de AniList y sin IDs locales.
- **Series**: cada ficha `(fuente, slug)` es una fila de la tabla `Ficha` (user-db, `FichaTable`) con el ID de su serie y su enlace con AniList; `SeriesIds`/`SeriesLedger` reparten los IDs. `SeriesResolver` une las fichas en una serie y `MergeProposals` propone uniones.
- **Reglas del usuario** (`UserTitles`, `TitleRules`, fichero `titles.json`): uniones («Series repetidas»), separaciones («No son la misma») y portadas elegidas.

### C. Emparejado con AniList (`source`)

- `AnilistMatcher` decide el anime de AniList de una ficha (nivel seguro, probable, dudoso o ninguno) comparando nombres, temporada, año y formato; una película, un OVA o un especial no se enlaza con una serie de TV. `AnilistSync` y `AnilistSyncRunner` lo ejecutan en segundo plano con el ritmo de AniList.
- **Pruebas**: `LinkEvidence`/`AnilistCompare` comparan año, episodios, géneros, estado, estudio, formato e `idMal` de la web con los del anime; con pruebas a favor y ninguna en contra se aplica solo; si no, va a revisión (Configuración › Series › Propuestas con AniList, `AnilistReview`).
- **Datos**: `AnimeFicha` (lo de AniList) y `WebFicha` (lo que dice cada web, `WebFichaSchema`) viven en `catalog-db`. `FichaData` es el único lector tipado de ese JSON. `FichaFill` completa en segundo plano las fichas de las webs para aportar pruebas al emparejado.
- **Semilla inicial (`AnilistSeed`)**: en una instalación limpia puede importar los enlaces automáticos firmados publicados por el repositorio satélite.
- `Animeoteca` aporta el texto en español de la ficha.

### D. Persistencia (`app.kaizen.database`)

| Base | Versión | Qué guarda | Si se borra |
| --- | --- | --- | --- |
| `UserDB` | 8 | Favoritos, historial y progreso (`FavoriteAV1`, `Record`) y la tabla `Ficha` (serie y enlace con AniList de cada ficha). | Se pierde lo del usuario: hay copias y sincronización (ver E). |
| `CatalogDB` | 3 | `AnimeFicha` y `WebFicha`: metadatos externos que se pueden volver a pedir. | No pierde nada del usuario. Cada cambio de esquema lleva migración Room con test. |
| `CacheDB` | 16 | Caché de peticiones HTTP, catálogo interno (`CatalogEntry`, `CatalogLink`), identidades y recientes. | Se reconstruye automáticamente. |

### E. Copias, Recuperación y Sincronización Multi-dispositivo

- **Sincronización multi-dispositivo WebDAV (`commons/sync/`)**:
  - `SyncManager`: orquesta la sincronización en segundo plano de favoritos, historial, progreso y marcas de series contra un servidor WebDAV privado.
  - Algoritmo funcional de convergencia (`Removals.step`): resuelve altas, bajas y modificaciones concurrentes sin pérdidas. Utiliza **lápidas con marca de tiempo** para garantizar que los borrados no resuciten al sincronizar dispositivos desconectados.
  - Poda automática de dispositivos abandonados: elimina del servidor copias de dispositivos que lleven más de 180 días inactivos sin peligro de borrado accidental masivo.
  - Almacenamiento seguro de credenciales con Android Keystore (`SecretStore`).
  - **Destinos** (`RemoteStore`): WebDAV (`WebDavStore`) o, sin servidor WebDAV, la carpeta de red SMB de las copias (`SmbStore`, `SyncManager.viaSmb`). Cada tele escribe solo su `kaizen-estados-<id>.json`. Se sincroniza al abrir la app (`BackupWork.schedule`) y con la copia diaria; `syncAuto` viene activo y solo actúa si hay destino.
  - **Uniones con AniList (`source/AnilistShare`, 18-nov)**: tras los estados, la misma carpeta lleva los enlaces aplicados (`kaizen-anilist-enlaces.json.gz`; nunca pisan un enlace existente ni uno del usuario) y las fichas de AniList en tramos de 5000 ids (`kaizen-anilist-fichas-<tramo>-<n>.jsonl.gz`; `n` es cuántas lleva, así se decide sin abrirlos si bajar o subir). Con enlaces nuevos se reagrupa el catálogo. Un fallo aquí no invalida la sincronización de estados.
- **Copias en una carpeta de red SMB (`commons/SmbBackup`, `SmbFolder`)**: las copias de estados (`kaizen-backup-*.json`) y de ajustes (`kaizen-ajustes-*.json`) se añaden a una carpeta `smb://servidor/recurso/carpeta` (jcifs, SMB 2/3, sin SMB 1 ni NetBIOS por difusión) y se bajan de ella para restaurar en otra tele. Solo añade; poda las más viejas según «Máximo de copias». Usuario y clave aparte (la clave cifrada con el Keystore). Se sube sola con la copia diaria si «Copiar también la copia diaria» está activo (`BackupWork`). Ajustes en Configuración › Copia de seguridad › Carpeta de red. Lo que decide qué subir, bajar y podar se prueba sin red con `RemoteFolder`.
  - **Asistente (`tv/settings/SmbPickerDialog`)**: una pantalla con servidor (sugerencias por mDNS `_smb._tcp`, `SmbDiscovery`, apagable en «Buscar servidores en la red»), invitado, usuario, contraseña (con «Mostrar» en el teclado) y carpeta compartida (`video/kaizen`, sin protocolo ni servidor; también vale `\\nas\video\kaizen`). «Examinar» recorre recursos y carpetas (`SmbFolder.folders()`); «Verificar y guardar» comprueba servidor, credenciales y ruta y solo entonces guarda (`SmbErrors` traduce los fallos a un texto que dice qué hacer). La dirección se guarda como `smb://…` pero se enseña sin protocolo (`SmbFolder.display`).
- **Copias en JSON estructuradas**:
  - `kaizen-backup-*.json`: **estados** del usuario (`KaizenBackup`: favoritos, historial, progreso, estados de las series, reglas de unión).
  - `kaizen-ajustes-*.json`: **ajustes** (`SettingsBackup`: los de `AdbSettings` + fuentes activas, orden de menús y botones, sin cookies ni estado interno).
- `LedgerBackup`, `AnilistLinksBackup` y `FichaExport`: salvaguarda periódica de enlaces y fichas costosas.
- `CrashGuard`: tras varios cierres seguidos restablece ajustes y caché, preservando siempre la base del usuario.

### F. Reproducción (`tv/exoplayer`, `videoservers`, `commons/KaizenProxy`)

- Extractores modulares en `videoservers/` (`VidHide`, `Filemoon`, `MixDrop`, `Savefile`, `Dood`, etc.).
- `ServerMemory`: recuerda el último servidor funcional por serie, facilitando la conmutación automática.
- `PlaybackController` y `PlayerScreen`: ExoPlayer (Media3), progreso milimétrico, cambio dinámico de refresco de pantalla (`FrameRateMatcher` / AFR) y aceleración configurable de salto temporal.
- **`AniSkip` robusto**: consulta la API de saltos de opening y ending validando que la duración reportada coincida con la duración real del vídeo (`forDuration`). Aplica regla temporal 50/50: un opening debe iniciar en la primera mitad del vídeo y un ending en la segunda mitad para evitar marcadores invertidos o falsos positivos.

### G. Interfaz de TV (`app.kaizen.tv`)

- Arquitectura 100% Compose TV (`androidx.tv.material`) navegable por control remoto: `TVMain`, `LandingScreen`, `SeriesDetailScreen`, `SearchPage`, `SourcesPage`, `StatsPage`, `SettingsPage`.
- **Estructura de la pantalla principal (`LandingScreen`)**:
  - **Menú lateral** (`KaizenSidebar`): solo secciones de contenido (Inicio, Favoritos, Mi lista, Últimos vistos, Recomendados, Mejores, Emisión, Fuentes); orden y visibilidad en Configuración › Pantalla › Secciones del menú (`MainSections`, `KaizenSettings.menuOrder/hiddenRows`). El foco blanco y la sección en uso en azul. Sin logo.
  - **Barra superior** (`TopBar`): a la derecha, iconos redondos de **Buscar** y **Configuración** (`TopIconButton`, no son entradas del menú; la sección en uso en azul) y el reloj. Al pulsarlos el foco entra en la página 200 ms después de componerla (pedirlo antes lo dejaba sin dueño y abría el menú lateral).
  - **Mejores** reúne «en emisión» y «de siempre» (`BestPage`, conmutador arriba); `MainSections.BESTGLOBAL` queda solo como id de la página.
  - **Emisión** (`EmissionPage`): una fila por día, el título del día entra en la vista (`includeTitleInView`), el de hoy lleva la etiqueta «Hoy» y el foco entra por el lunes (`focusProperties { enter }`).
- **Configuración (`tv/settings`)**: rejilla de nueve tarjetas **sin subtítulos** (`SettingsPage`); cada una abre un panel lateral (`SettingsPanel`, modelo `SettingItem`: `CheckItem`, `ChoiceItem`, `ActionItem`, `ConfirmItem`, `MultiChoiceItem`, `TextItem`, `ReorderItem`, `SubMenuItem`) con **un solo nivel de submenús** y miga de pan. Un fichero por categoría (`PlayerSettings`, `ScreenSettings`, `NetworkSettings`, `DataSettings`, `BackupSettings`, `AboutSettings`, `LibrarySettings`). Al cerrar un panel o pantalla, el foco vuelve a su tarjeta.
  - **Biblioteca** = panel lateral (`LibrarySettingsItems`: resumen, Dudas de AniList, Series unidas, Fuentes y los submenús Emparejado con AniList y Mantenimiento; cada cifra una sola vez, cargadas por `LibraryStats`) + tres pantallas completas sin cabecera (`LibraryDialog`, `LibraryScreen`: dudas = `PendingTab`, unidas = `JoinedTab`, fuentes = `SourcesTab`).
  - Preparado y oculto tras `SettingsFlags`: modo parental «Contenido sensible» (géneros ecchi/hentai + marcado manual, blur) y «Actualizaciones» (`KaizenSettings.sensitive*`, `updateMode`); «Contenido sensible» ya funciona (18-nov, ver más abajo); «Actualizaciones» sigue sin lógica.
  - Copia de seguridad en Local / Remota / Mis ajustes (incluye «guardar como predeterminados», `SettingsBackup.saveDefaults/restoreDefaults`) / Restablecer.
- **Modo parental «Contenido sensible» (18-nov)**: desenfoque de carátulas de series adultas o de géneros ecchi/hentai de AniList (`AnilistCard.sensitive`), o marcadas a mano, en tarjetas (`KaizenPosterCard.rememberSensitive`) y ficha. Lo hace `commons/SensitiveBlur`, una `Transformation` de Coil3 (reduce y vuelve a ampliar) porque `Modifier.blur` exige Android 12 y la TV es Android 9. Solo actúa en series enlazadas con AniList; apagado de fábrica.
- **Menú contextual de tarjetas (`tv/ui/CardMenu`)**: con un capítulo, dos bloques con encabezado, «Capítulo N» (Reproducir, Elegir servidor, Marcar visto, Quitar de seguir viendo) y «Serie · nombre» (Abrir ficha, Favorito, Estado, No recomendar, Unir); con una serie sola, sin encabezados.
- **Ventana de Configuración**: `SettingsDialog` fija la ventana del `Dialog` a pantalla completa (`setLayout(MATCH_PARENT, MATCH_PARENT)`) para evitar una franja horizontal al cerrar un submenú (hipótesis, sin reproducir con capturas).
- **Estado «en emisión»**: lo decide AniList (`FichaData.estado`, `FichaPolicy.finished`, `AnilistFicha.of`), no `series.day`.
- **Etiquetas de capítulo** (`KaizenBadge`, `KaizenPosterCard`): «Novedad» (primer capítulo) y «Final» (último).
- **Reproductor**: «Capítulo anterior» vuelve primero al inicio y, con una segunda pulsación, al capítulo anterior (`previousOrRestart`); «Saltar cierre» en el último capítulo cierra el reproductor (`closeIfLast`).
- **Recomendaciones** (`domain/Recommendations`, `Taste`): mezclan géneros y primeras páginas de búsqueda (`SearchEngine.firstPages`) para dar variedad.
- **Menú unificado «Copia y sincronización»**: jerarquía estructurada con `SubMenuItem` (Crear copia, Restaurar, Sincronizar con otros dispositivos, Repositorio de descargas, Opciones de retención y Restablecer).
- **Lógica de estados de serie (`domain/StatusChoice`)**:
  - Marcar una serie como «Completada» marca automáticamente todos sus episodios como vistos.
  - Si la serie está en emisión, pasa a «Viendo» con todos los capítulos emitidos marcados y se auto-completa sola al finalizar la emisión (`autoCompleted`).
  - Desmarcar un capítulo de una serie completada la reabre automáticamente a «Viendo» (`autoReopen`).
- **Componentes comunes**:
  - `KaizenBadge`: chapas «Novedad» (capítulo 1) y «Final» (último capítulo) en las tarjetas de capítulos.
  - `KaizenLegend`: leyendas visuales compartidas (`Legend`, `MatchLegend`, `LogLegend`).
  - `LoadState`: patrón `Loadable` (`Loading`, `Failed`, `Ready`) para pantallas reactivas.
  - `TextEditDialog`: teclado en pantalla para editar URLs y textos con mando a distancia.

### H. Segundo plano y control (`jobscheduler`, `control`, `commons`)

- `RecentsWork`, `BackupWork`, `AnilistSyncRunner`, `FichaFill`, `CatalogBuilder`.
- `DebugLog`: registro continuo en formato JSON Lines (`debug.jsonl`).
- `AppErrors`: gestor único de errores tipados E01 a E99 con contexto de fallo, severidad y remediación.
- `commons/warp`: túnel WireGuard/WARP encapsulado en el proceso de Kaizen.
- `AdbControlReceiver`: receptor de automatización y administración remota por ADB.

---

## 4. Herramientas y Calidad

- **Compilar y probar**: `./gradlew --no-daemon :app:assembleTv` y `:app:testDebugUnitTest`.
- **Publicación y auditoría**: `node tools/ship.js check` y `ship.js publish` (ver [tools/README.md](../../tools/README.md)).
- **Hardware real**: `node tools/tv.js deploy` (instalación segura con `-r -f` en memoria interna) y `bench.js` (medición repetible de arranque).
- **Suite de auditorías**: `tools/audit/audit.py` y `code_graph.py` (análisis estático de dependencias y código muerto).

---

## 5. Preferencias Guardadas: Un Solo Acceso

`commons/KaizenStore` es la única puerta de acceso a `SharedPreferences`: cada archivo pertenece a una categoría estricta (`SETTINGS`, `STATE`, `CACHE`, `SECRET`). Se audita con `node tools/tv.js store` por ADB.
