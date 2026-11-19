# Codebase Map: Kaizen (`docs/CODEBASE-MAP.md`)

Orientación rápida para quien llega al proyecto: puntos de entrada, paquetes, dónde vive cada cosa y cómo se comprueba. Actualizado el 2026-11-14.

---

## 1. Puntos de entrada

| Punto de entrada | Ruta | Propósito |
| --- | --- | --- |
| **Application** | `app/src/main/java/app/kaizen/App.kt` | Contexto global, bases de datos (Room), cliente HTTP único, CrashGuard y arranque de temporizadores. |
| **Manifiesto TV** | `app/src/tv/AndroidManifest.xml` | Sustituye al de `main` en la variante TV (intent `LEANBACK_LAUNCHER`, banner 16:9 y recursos específicos de Leanback en `app/src/tv/res`). |
| **Pantalla de TV** | `tv/TVBaseActivity.kt`, `tv/ui/TVMain.kt`, `tv/ui/screens/LandingScreen.kt`, `tv/ui/components/KaizenSidebar.kt`, `tv/ui/theme/` | Inicio, estantes de series, rejillas ordenadas y paginadas (`tv/ui/ListPaging.kt`, una sola paginación para todas las listas; `tv/BestAiringCache.kt` guarda «Mejores en emisión» una semana), fichas, búsqueda y ajustes, 100% navegables con D-Pad / mando a distancia. |
| **Reproductor** | `tv/exoplayer/` (`PlayerScreen`, `PlaybackController`) y `player/WebPlayerActivity.kt` | ExoPlayer (Media3) con soporte para refresco automático de pantalla (AFR), aceleración de salto y reproductor web de reserva. |
| **Receptor ADB** | `control/AdbControlReceiver.kt` | Receptor de comandos remotos (`am broadcast` a `app.kaizen.action.*`): `BACKUP`, `STATUS`, `EXPORT_FICHAS`, `WALK`, `IMPORT`, `SPLIT`, `FICHA_GET`, `FILL_FICHAS`, `SETTINGS`, `SETTING_GET`, `SETTING_SET`, `ANILIST_AUTO`, `WARP`, `SOURCES`, `SOURCES_CHECK`, `ERRORS`, `BYPASS`, `CACHE_CLEAR`, `LOG_CLEAR`, `SETTINGS_RESET`, `LALIGA`, `IMAGE_MISSES`, `STORE` (requiere activar «Control por ADB» en Configuración › Depuración). |
| **Verificación de webs** | `source/SourceChallenge.kt`, `tv/ui/VerifySourceDialog.kt` | Detección de Cloudflare/anti-bot (403 con `{"challenge":true}`). Se delega la resolución al usuario en WebView, preservando cookies y User-Agent para futuras peticiones. |

Rutas relativas a `app/src/main/java/app/kaizen/` salvo que se indique otra cosa.

---

## 2. Paquetes

```text
app/kaizen/
├── source/        # Fuentes, catálogo, series, emparejado con AniList y semillas portables (ver §3)
├── database/      # Room: CatalogDB (catalog-db v3), UserDB (user-db v8), CacheDB (cache-db v16) y sus DAO
├── domain/        # Modelos puros y reglas de dominio (StatusChoice, StatusMigration, Library, WatchState, Recommendations)
├── tv/            # Interfaz de Android TV (Compose)
│   ├── ui/        # Pantalla principal, estantes, diálogos, estados de carga (LoadState) y componentes
│   │   ├── components/ # Tarjetas (KaizenPosterCard), menú lateral (KaizenSidebar, solo secciones; Buscar y Configuración son iconos de la barra superior en LandingScreen), leyendas (KaizenLegend), chapas (KaizenBadge)
│   │   ├── screens/    # LandingScreen, SeriesDetailScreen
│   │   └── theme/      # KaizenColors, tipografías y escalas de interfaz
│   ├── details/   # Ficha de una serie (galería de portadas, calidad de imagen, metadatos resueltos)
│   ├── settings/  # Configuración (9 tarjetas, un fichero por categoría: PlayerSettings, ScreenSettings, NetworkSettings, DataSettings, BackupSettings, AboutSettings; submenús SubMenuItem) y Biblioteca (panel lateral LibrarySettings + tres pantallas completas en LibraryDialog: dudas, series unidas, fuentes)
│   ├── exoplayer/ # Reproductor Media3 (PlaybackController, PlayerScreen, AFR, AniSkip)
│   ├── search/, anime/, cards/, streaming/
├── commons/       # Infraestructura común
│   ├── sync/      # Sincronización multi-dispositivo WebDAV o SMB (SyncManager, SyncDb, lápidas Removals.step, RemoteStore)
│   ├── Smb*.kt    # Copias de seguridad en una carpeta de red SMB (SmbBackup, SmbFolder con jcifs, SmbErrors, SmbDiscovery por mDNS; sync/SmbStore sincroniza por esa carpeta); asistente en tv/settings/SmbPickerDialog
│   ├── SensitiveBlur.kt # Desenfoque de carátulas (modo parental «Contenido sensible»): Transformation de Coil3: reduce a 96 px, tres desenfoques de caja y amplía; válida en Android 9
│   ├── warp/      # Túnel WireGuard/WARP integrado exclusivo para Kaizen (WarpTunnel, WarpPolicy)
│   ├── HTTP: AppHttpClient, Network, FallbackDns, HostGate, TlsPolicy, RequestCounter
│   ├── Almacenamiento: KaizenStore (puerta única a SharedPreferences), KaizenSettings, Tuning, SecretStore
│   ├── Copias y estados: KaizenBackup, SettingsBackup, RecentsArchive, KaizenProgress
│   └── Diagnóstico: DebugLog (JSON Lines), ErrorLog, AppErrors (gestor único E01-E99), ImageMisses, CrashGuard
├── videoservers/  # Extractores de servidores de vídeo (VidHide, Filemoon, MixDrop, Savefile, StreamWish, etc.)
├── jobscheduler/  # WorkManager: RecentsWork (capítulos recientes) y BackupWork (copias periódicas)
├── domain/, home/, recents/, pojos/, retrofit/, search/, bypass/, custom/, uagen/
└── control/       # Automatización y control externo por ADB (AdbSettings, AdbControlReceiver)
```

---

## 3. `source/`: Fuentes, Catálogo, Series y AniList

| Tema | Ficheros Clave | Responsabilidad |
| --- | --- | --- |
| **Fuentes** (definidas en JSON) | `ConfigurableSource`, `spec/` (`SourceSpec`, `RepoConfig`), `SourceHttp`, `SourceChallenge`, `AnimeSource` | Motor único de extracción basado en especificaciones declarativas (`assets/sources/*.json`). Reintentos, cookies de bypass y pausas automáticas. |
| **Catálogo** (fichas recorridas de las fuentes) | `Catalog`, `CatalogBuilder`, `CatalogTables`, `CatalogState`, `CatalogSnapshot`, `CatalogSeed` | Recorrido periódico (cada 14 días) y agrupación en series. `CatalogSeed` permite descargar e importar catálogos unificados portables y firmados sin esperar al recorrido. |
| **Identidad de las series** (tabla `Ficha` en `user-db`) | `SeriesIds`, `SeriesResolver`, `FichaTable`, `UserRows`, `SeriesDedupe`, `SeriesMatch`, `MergeProposals`, `UserTitles` | Asignación de ID único por serie. Las reglas de unión automática exigen coincidencia con AniList; el usuario puede unir o separar mediante `titles.json`. |
| **Emparejado con AniList** | `AnilistMatcher`, `AnilistSync`, `AnilistSyncRunner`, `AnilistReview`, `AnilistCompare`, `LinkEvidence`, `AnilistSeed`, `AnilistShare` (enlaces y fichas por la carpeta de sincronización) | Enlace por evidencia (nombre, año, temporada, episodios, formato, `idMal`). Lo seguro se aplica solo; lo dudoso va a revisión. `AnilistSeed` provee enlaces pre-calculados firmados para instalaciones limpias. |
| **Esquema de datos web** (`WebFicha`) | `WebFichaSchema`, `FichaData`, `FichaResuelta`, `WebFichaQuery`, `FichaFill` | `WebFicha` almacena estrictamente lo que la web declara en un JSON unificado. `FichaResuelta` combina AniList con datos web para visualización sin contaminar la entidad. `FichaFill` completa fichas de las webs en segundo plano. |
| **Animeoteca** | `Animeoteca` | Enriquecimiento opcional de sinopsis y títulos en español. |
| **Saltos de cabecera** | `AniSkip` | Detección de openings/endings con validación estricta de duración del vídeo (`forDuration`) y regla 50/50 (openings en la primera mitad, endings en la segunda). |

---

## 4. Fuentes Soportadas (`app/src/main/assets/sources/`)

Fuentes empaquetadas con firma ECDSA:
- `animeav1.json`: Extracción mediante datos en JavaScript (`jsData`).
- `animeflv.json` (animeflv.one) y `animeflvar.json` (animeflv.ar, extracción con iframes codificados).
- `animegratis.json`, `jkanime.json`, `monoschinos.json`, `verani.json`.
- `index.json` e `index.json.sig`: Manifiesto firmado que valida la autenticidad de las fuentes.

---

## 5. Herramientas del Proyecto (`tools/`)

Ver la guía exhaustiva en [tools/README.md](../tools/README.md):
- **`tv.js`**: Operaciones completas en el tvbox (`deploy`, `open`, `logs`, `logcat`, `awake`, `health`, `backup`, `status`, `export-fichas`, `fill-fichas`, `ficha`, `series`, `settings`, `setting`, `sources`, `anilist-auto`, `store`, `calls`, `warp`, `errors`, `bypass`, `laliga`, etc.).
- **`ship.js`**: Publicación controlada en 2 fases (`check`, `publish`, `docs`, `push`) con verificación obligatoria de secretos, pruebas y compilación.
- **`bench.js`**: Banco de pruebas de arranque en frío y consumo de CPU en la TV.
- **`make-catalog-seed.py` / `make-links-seed.py`**: Generación y firma de semillas portables del catálogo y de enlaces con AniList.
- **`sign-sources.sh`**: Firma ECDSA P-256 del manifiesto de fuentes.
- **`probe_servers.py` / `ficha-preview.sh`**: Verificación de servidores de vídeo y visualización previa de scraping desde el PC.
- **`retrace.js`**: Desofuscación de trazas de error con mappings de R8.
- **`tools/audit/`**: Suite de análisis estático y verificación de base de datos (`audit.py`, `code_graph.py`, `query_plans.py`, `unused_resources.py`).
- **Conectores RSC**: `tools/adb-device/` (conexión TV) y `tools/basic-memory/` (sincronización con Basic Memory MCP).

---

## 6. Procesos en Segundo Plano y Sincronización

1. **Recorrido del catálogo** (`CatalogBuilder`): Automático al arrancar si el catálogo tiene más de 14 días o está vacío. Controlable desde Configuración.
2. **Emparejado con AniList** (`AnilistSyncRunner`): Por lotes en segundo plano respetando las cuotas de AniList y pausas de actividad.
3. **Sincronización multi-dispositivo** (`commons/sync/SyncManager`): Sincroniza historial, favoritos y progreso contra un servidor WebDAV privado. Algoritmo funcional de convergencia (`Removals.step`), lápidas de borrado y eliminación segura de dispositivos abandonados (>180 días).
4. **Tareas periódicas WorkManager**:
   - `RecentsWork`: Actualiza la fila de capítulos recientes.
   - `BackupWork`: Genera copias de seguridad locales rotativas.
5. **Wakelock y reposo**: El reproductor libera la pantalla y cede el paso al protector de pantalla tras 10 minutos en pausa.

---

## 7. Comprobar un Cambio

Flujo canónico de verificación antes de dar por buena cualquier modificación:

```bash
# 1. Pruebas unitarias en la JVM
./gradlew --no-daemon :app:testDebugUnitTest

# 2. Compilación de la variante TV de producción
./gradlew --no-daemon :app:assembleTv

# 3. Comprobación integral de seguridad y generación de SHA de auditoría
node tools/ship.js check

# 4. Despliegue directo en el tvbox (con flags -r -f en almacenamiento interno)
node tools/tv.js deploy

# 5. Descarga de registros y diagnóstico de errores en la TV
node tools/tv.js logs
```
