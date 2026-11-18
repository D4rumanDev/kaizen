# Herramientas de Kaizen (`tools/`)

Carpeta unificada de utilidades, scripts de mantenimiento, herramientas de auditoría y conectores de hardware/servicio para **Kaizen**. Todas las herramientas se ejecutan desde la **raíz del repositorio** (`./` en Linux/macOS o PowerShell en Windows).

---

## Índice Rápido

1. [Control y Despliegue en Android TV (`tv.js`)](#1-control-y-despliegue-en-android-tv-tvjs)
2. [Publicación y Auditoría de Código (`ship.js`)](#2-publicación-y-auditoría-de-código-shipjs)
3. [Medición de Rendimiento y Arranque (`bench.js`)](#3-medición-de-rendimiento-y-arranque-benchjs)
4. [Semillas Portables y Firma de Catálogo](#4-semillas-portables-y-firma-de-catálogo)
   - [`make-catalog-seed.py`](#make-catalog-seedpy)
   - [`make-links-seed.py`](#make-links-seedpy)
   - [`sign-sources.sh`](#sign-sourcessh)
5. [Pruebas de Vídeo y Extracción Web](#5-pruebas-de-vídeo-y-extracción-web)
   - [`probe_servers.py`](#probe_serverspy)
   - [`ficha-preview.sh`](#ficha-previewsh)
6. [Depuración y Registro](#6-depuración-y-registro)
   - [`retrace.js`](#retracejs)
7. [Suite de Auditorías (`tools/audit/`)](#7-suite-de-auditorías-toolsaudit)
   - [`audit.py`](#auditpy-orquestador-principal)
   - [`code_graph.py`](#code_graphpy)
   - [`unused_resources.py`](#unused_resourcespy)
   - [`query_plans.py`](#query_planspy)
   - [`ficha_sample.py` y `ficha_keys.py`](#ficha_samplepy-y-ficha_keyspy)
   - [`format_search.py`](#format_searchpy)
8. [Edición, Formato y Utilidades de Desarrollo](#8-edición-formato-y-utilidades-de-desarrollo)
   - [`edit.py`](#editpy)
   - [`backslash.py`](#backslashpy)
   - [`audit-diff.js`](#audit-diffjs)
   - [`test-count.js`](#test-countjs)
   - [`vscode-java.js`](#vscode-javajs)
   - [`vscode-extensions.ps1`](#vscode-extensionsps1)
   - [`make_brand.py`](#make_brandpy)
9. [Conector ADB (`tools/adb-device/`)](#9-conector-adb-toolsadb-device)

---

## 1. Control y Despliegue en Android TV (`tv.js`)

Herramienta principal de interacción directa con la **tvbox** (u otro Android TV) a través de ADB. Conecta automáticamente usando `ADB_DEVICE` configurado en `tools/adb-device/.env` o la variable de entorno del sistema.

```bash
node tools/tv.js SUBCOMANDO [OPCIONES]
```

### Subcomandos Disponibles

| Subcomando | Propósito | Opciones / Argumentos | Requiere «Control por ADB» |
|---|---|---|:---:|
| `deploy` | Compila (opcional), instala con `-r -f` (almacenamiento interno), arranca la app y valida que esté viva sin crashes (`FATAL EXCEPTION` / `ANR`). | `--build`: compila `:app:assembleTv` antes de instalar.<br>`--apk <ruta>`: usa un APK concreto (por defecto `app/build/outputs/apk/tv/app-tv.apk`).<br>`--wait <seg>`: espera tras arrancar antes de comprobar (por defecto 10 s).<br>`--device <IP:puerto>`: sobrescribe el dispositivo objetivo. | No |
| `open` | Despierta la pantalla (`KEYCODE_WAKEUP`) y abre Kaizen en primer plano (`LEANBACK_LAUNCHER`). Comprueba que el proceso quede activo. | Ninguna | No |
| `logcat` | Muestra el logcat filtrado en vivo en la terminal: `Kaizen:V`, `CrashGuard:V`, `ErrorLog:V`, `AndroidRuntime:E`. | Ninguna | No |
| `clear-logcat` | Limpia el buffer circular del logcat en la TV (`adb logcat -c`). | Ninguna | No |
| `logs` | Descarga `debug.jsonl` (JSON Lines), su versión plana `debug.log` y el logcat a `backups/tv-logs/<fecha>/`. Resume en pantalla los fallos por tipo, errores del gestor único (E01…), origen y webs caídas con su código HTTP. | `--out <carpeta>`: carpeta de destino.<br>`--lines <N>`: líneas de logcat a capturar (por defecto 3000). | No |
| `awake` | Controla la suspensión del tvbox para tareas largas en segundo plano (p. ej., sincronización masiva o emparejado AniList). | `on`: pone pantalla encendida indefinida, guarda los valores previos en `backups/tv-awake-anterior.json`.<br>`off`: restituye exactamente los ajustes del sistema previos.<br>`status`: muestra los tiempos de apagado y estado actual. | No |
| `health` | Informe integral de salud de la app (solo lectura). Cuenta patrones críticos en los registros (`readNBytes`, `HostGate`, aplazadas, `HTTP 403/404`, crashes) y los compara con el informe anterior en `backups/health/`. | `--no-logs`: usa el último registro descargado sin volver a conectar a la TV. | Recomendado |
| `backup` | Ejecuta la copia completa de la app (`BACKUP` por ADB), espera a que se genere en la TV, la descarga con `adb pull` a `backups/tv-<fecha>` y valida su integridad y descompresión. | `--confirm`: sin este flag solo muestra el plan de copia sin escribir nada. | **Sí** |
| `status` | Consulta los recuentos internos de la app por ADB: favoritos, historial de vistos, fichas descargadas de AniList y fichas de las webs (`WebFicha`). | Ninguna | **Sí** |
| `export-fichas` | Exporta todas las fichas de AniList (`AnimeFicha`) de `catalog-db` a `backups/fichas/fichas-anilist-<fecha>.jsonl.gz`, espera a que termine de escribirse en la TV y la comprueba en el PC. | Ninguna | **Sí** |
| `fill-fichas` | Abre en la TV las fichas de las webs que están por revisar con AniList y no tienen datos locales, a ritmo controlado (1,5 s), con pausas `HostGate` y repartidas entre fuentes. | `--limit <N>`: máximo de fichas a procesar (1 a 200, por defecto 20).<br>`--fuente <id>`: filtra por una fuente concreta. | **Sí** |
| `ficha` | Consulta los datos guardados en `WebFicha` (con el mismo código `WebFichaQuery` de la app) por cualquier coincidencia de nombre o ruta JSON. | `NOMBRE [RUTA] [--resuelta]`<br>`NOMBRE`: texto a buscar (título, sinónimo, etc.).<br>`RUTA`: campo o subruta (ej. `generos`, `anio`, `web.score`).<br>`--resuelta`: si está enlazada, prioriza datos de AniList completando con la web. | **Sí** |
| `series` | Abre directamente en pantalla *Configuración › Biblioteca › Por revisar › Dudas de AniList*. Consulta AniList y resuelve automáticamente lo evidente hasta la primera ambigüedad. | `--wait <seg>`: tiempo de espera antes de la captura (por defecto 12 s).<br>`--shot`: guarda captura de pantalla en `backups/capturas/`. | No |
| `anilist-auto` | Revisa sin pantalla las series dudosas con AniList. | Sin flags: muestra el avance.<br>`--start`: cuenta cuántas se aceptarían, descartarían o preguntarían sin escribir.<br>`--start --apply --confirm`: crea una copia de seguridad previa, guarda los enlaces claros y reagrupa el catálogo. | **Sí** |
| `settings` | Lista todos los ajustes del usuario configurables por ADB, con sus valores actuales y opciones permitidas. | Ninguna | **Sí** |
| `setting` | Lee o modifica un ajuste validado contra la lista cerrada de `AdbSettings`. | `NOMBRE [VALOR]`: si se omite valor, lee el ajuste. | **Sí** |
| `sources` | Lista las fuentes activas/inactivas, permite activar/desactivar una fuente o medir la latencia y estado de todas las activas. | `[ID on\|off \| --check]`<br>Sin argumentos: lista fuentes.<br>`animeav1 off`: desactiva la fuente.<br>`--check`: mide respuestas reales. | **Sí** |
| `store` | Lista los ficheros de preferencias administrados por `KaizenStore` (ajustes, estado, caché, secretos), número de claves y tamaño. Detecta ficheros huérfanos o no registrados. | Ninguna | **Sí** |
| `calls` | Consulta el histórico de llamadas HTTP realizadas a cada dominio en el día, separando uso interactivo de tareas en segundo plano. | `--day AAAA-MM-DD`: filtra por fecha (por defecto hoy).<br>`--urls`: muestra las rutas completas (solo de fuentes, nunca con parámetros). | **Sí** |
| `warp` | Comprueba el estado del túnel WARP (WireGuard exclusivo para Kaizen: modo, cuenta, estado de VPN). | `--evaluate`: fuerza la evaluación y conexión inmediata según el modo configurado. | **Sí** |
| `errors` | Resumen de los errores producidos durante la sesión clasificados por el gestor único (códigos E01 a E99). | Ninguna | **Sí** |
| `bypass` | Muestra el estado del bypass de Cloudflare, cookies almacenadas y vigencia de sesiones de navegador. | Ninguna | **Sí** |
| `laliga` | Comprueba si alguna fuente activa resuelve actualmente a una IP bloqueada por partidos de LaLiga (según lista dinámica de `hayahora.futbol`). | Ninguna | **Sí** |
| `image-misses` | Informa de las imágenes inexistentes (404/410) recordadas para evitar reintentos innecesarios durante 7 días. | `--clear`: vacía la caché de imágenes fallidas. | **Sí** |
| `clear-cache` | Limpia la base de datos de caché (`cache-db`) de la aplicación. | `--confirm`: requiere confirmación para ejecutar. | **Sí** |
| `clear-log` | Vacía el registro de depuración (`debug.jsonl`) en el almacenamiento de la app. | `--confirm`: requiere confirmación para ejecutar. | **Sí** |
| `reset-settings` | Restablece los ajustes a los valores de fábrica predeterminados. | `--confirm`: requiere confirmación para ejecutar. | **Sí** |

> [!NOTE]
> Para los comandos marcados con **«Requiere Control por ADB»**, es imprescindible activar previamente la casilla en la TV: *Configuración › Depuración › Control por ADB*.

---

## 2. Publicación y Auditoría de Código (`ship.js`)

`tools/ship.js` garantiza que ningún cambio se publique sin comprobación rigurosa de secretos, IPs locales, pruebas unitarias y compilación de producción, generando un identificador criptográfico (`SHA`) inmutable para auditoría.

```bash
node tools/ship.js SUBCOMANDO [OPCIONES]
```

### Subcomandos

1. **`check`**:
   Ejecuta la verificación previa de seguridad y salud del repositorio:
   ```bash
   node tools/ship.js check [--add ruta ...] [--add-new] [--exclude ruta ...] [--skip-tests]
   ```
   - Detecta y bloquea IPs locales privadas, nombres de usuario de host, ficheros `.env`, keystores o contraseñas.
   - Ejecuta las pruebas unitarias: `./gradlew --no-daemon :app:testDebugUnitTest`.
   - Compila la variante de producción TV: `./gradlew --no-daemon :app:assembleTv`.
   - Genera el SHA único del parche en `build/ship-check.json`.
   - `--add-new`: incluye automáticamente todos los archivos sin seguimiento (untracked).

2. **`publish`**:
   Publica el cambio previamente auditado mediante un commit y push verificado:
   ```bash
   node tools/ship.js publish -m "Mensaje del commit" --audited <SHA> [--trailer "Co-Authored-By: ..."] [--install]
   ```
   - Exige que el árbol coincida exactamente con el SHA auditado.
   - Realiza `git push` asegurando que no se use `--force` y que sea un avance rápido (*fast-forward*).
   - `--install`: tras el push exitoso, despliega automáticamente el APK en la TV (`tv.js deploy`).

3. **`docs`**:
   Cierre exclusivo de documentación con commit local (sin push):
   ```bash
   node tools/ship.js docs -m "Mensaje de documentación" RUTA [RUTA ...] [--no-sync] [--dry-run]
   ```
   - Verifica finales de línea y datos sensibles.
   - Si hay un replicador de documentación local configurado, lo ejecuta (es opcional y no se versiona).
   - Rechaza archivos sensibles como `constitution.md`, `CLAUDE.md` o `THIRD_PARTY.md`.

4. **`push`**:
   Publica commits locales acumulados (por ejemplo, creados con `docs`):
   ```bash
   node tools/ship.js push [--audited <SHA>]
   ```
   - Comprueba el rango `origin/<rama>..HEAD`, verifica pruebas si hubo cambios en código o Gradle y sube los cambios.

---

## 3. Medición de Rendimiento y Arranque (`bench.js`)

Proporciona mediciones científicas y repetibles del tiempo de arranque en frío (*cold start*) y del consumo de CPU en reposo directamente sobre el tvbox:

```bash
node tools/bench.js start [--runs 5] [--idle 90] [--device IP:puerto]
```

- **Mecánica**: Realiza `am force-stop` seguido de `am start -W` midiendo `TotalTime`, `ThisTime` y `WaitTime`. Durante los siguientes `--idle` segundos muestrea el uso de CPU leyendo `/proc/PID/stat` cada 5 segundos.
- **Resultado**: Informa de la mediana, mínimo y máximo del arranque, pico de CPU, segundos por encima del 50% y tiempo hasta estabilización por debajo del 15%.
- **Recomendación**: La TV debe estar despierta (`node tools/tv.js awake on`) y sin reproducción activa.

---

## 4. Semillas Portables y Firma de Catálogo

### `make-catalog-seed.py`
Genera el fichero portable y firmado del catálogo unificado (`catalogo-unificado.json.gz` y `catalogo-unificado.sig`) para instalaciones limpias o sincronización remota:

```bash
python tools/make-catalog-seed.py <catalog-snapshot.json.gz> <anilist-links.json.gz> <carpeta-salida> [--no-sign] [--publish <repo-sources>]
```
- **Entrada**: Snapshot del catálogo y copia de enlaces extraídos con `node tools/tv.js backup`.
- **Salida**: Catálogo agrupado por IDs neutros de AniList (sin identificadores locales de base de datos) y firmado con clave ECDSA P-256 (`~/.android/kaizen-sources-signing.pem` o `KAIZEN_SOURCES_KEY`).
- `--no-sign`: escribe el archivo comprimido sin firmar (solo para inspección).
- `--publish <ruta>`: copia automáticamente el catálogo y su firma al repositorio satélite `kaizen-sources`.

### `make-links-seed.py`
Genera y firma la semilla inicial de enlaces de AniList (`enlaces-anilist.json.gz` + `.sig`):

```bash
python tools/make-links-seed.py <anilist-links.json.gz> <carpeta-salida> [--matcher N]
```
- Filtra exclusivamente enlaces automáticos de alta fiabilidad (`seguro`, `probable`, etc.), omitiendo uniones manuales del usuario.

### `sign-sources.sh`
Regenera el índice firmado de las fuentes (`app/src/main/assets/sources/index.json` y `index.json.sig`):

```bash
bash tools/sign-sources.sh [--publish <ruta-repo-sources>]
```
- Lee todos los JSON de fuentes, calcula sus hashes SHA-256, crea el manifiesto de versión y lo firma criptográficamente con ECDSA P-256.

---

## 5. Pruebas de Vídeo y Extracción Web

### `probe_servers.py`
Prueba rápida desde el PC para verificar la disponibilidad y extractores de servidores de vídeo (VidHide, Filemoon, MixDrop, Savefile, Dood, etc.) contra capítulos reales:

```bash
python tools/probe_servers.py [URL_DE_CAPITULO ...]
```
- Resuelve utilizando DNS sobre HTTPS (DoH 1.1.1.1) para saltar bloqueos locales.
- No ejecuta JavaScript; desempaca algoritmos `p,a,c,k,e,d` mediante análisis léxico en Python.
- Si se ejecuta sin argumentos, analiza una lista curada de capítulos de prueba en `DEFAULT_PAGES`.

### `ficha-preview.sh`
Vista previa sin instalación ni acceso a base de datos de lo que extraería el scraper de Kaizen:

```bash
bash tools/ficha-preview.sh <URL_DE_LA_FICHA>
```
- Descarga la página web y la ejecuta a través del motor real de la app (`JsExtractor` con Rhino, `SourceSpec` y `readFicha`) mediante la suite de tests unitarios JVM (`FichaPreviewTest`).
- Imprime la estructura `WebFicha` completa generada con todas sus columnas indexadas y la entidad `Series` resultante.

---

## 6. Depuración y Registro

### `retrace.js`
Desofusca trazas de excepciones generadas por compilaciones minificadas con R8 en el tvbox:

```bash
node tools/retrace.js save
node tools/retrace.js trace <RUTA_A_DEBUG_LOG>
```
- `save`: Almacena el `mapping.txt` de la última compilación en `backups/mapping/<id>.txt` indexado por su `pg_map_id`.
- `trace`: Analiza un fichero de registro (`debug.log`), detecta los IDs de compilación (`r8-map-id-XXXXXXXX`) y traduce automáticamente la pila de llamadas utilizando la herramienta `retrace` del Android SDK.

---

## 7. Suite de Auditorías (`tools/audit/`)

Conjunto de utilidades de solo lectura para auditar la base de datos, relaciones de AniList y salud del código fuente.

### `audit.py` (Orquestador Principal)
Punto de entrada unificado para todas las auditorías sobre volcados de datos:

```bash
python tools/audit/audit.py TEMA [ARGUMENTOS]
```

| Tema | Módulo Subyacente | Qué Audita |
|---|---|---|
| `unions` | `unions.py` | Analiza las uniones de series discriminando uniones automáticas por AniList frente a uniones manuales del usuario. |
| `slugs` | `slugs.py` | Audita los marcadores de temporada, parte y OVA en los slugs de las fuentes para detectar patrones no soportados por el analizador. |
| `match` | `match.py` | Evalúa sobre una muestra cuántas series del catálogo local podrían enlazarse automáticamente con AniList. |
| `pending` | `pending.py` | Analiza las fichas que permanecen «por revisar» y proyecta cuántas se resolverían con cambios en el matcher. |
| `unmerge` | `unmerge.py` | Compara un volcado anterior y uno posterior para generar el fichero JSON que deshace uniones erróneas mediante la acción ADB `SPLIT`. |
| `check-migration` | `check_migration.py` | Simula con SQLite en memoria las migraciones de Room (`user-db` 7→8 y `cache-db` 15→16) contra los esquemas exportados. |

### `code_graph.py`
Analizador estático de dependencias y código muerto sobre `app/src/main`:

```bash
python tools/audit/code_graph.py dead [--apply]
python tools/audit/code_graph.py unused [--apply-imports]
python tools/audit/code_graph.py html [--out backups/code-graph/]
python tools/audit/code_graph.py who <CLASE_O_FUNCION>
python tools/audit/code_graph.py deps <CLASE_O_FUNCION>
```
- `dead`: Localiza funciones, clases y constantes inalcanzables. Con `--apply` elimina el código muerto. Respeta las excepciones definidas en `tools/audit/code_graph_keep.txt`.
- `unused`: Detecta imports, variables y parámetros sin utilizar.
- `html`: Genera un visualizador web interactivo de dependencias del proyecto.

### `unused_resources.py`
Inspecciona recursos XML en `res/values*` que no son referenciados en ningún composable o diseño:
```bash
python tools/audit/unused_resources.py [--apply]
```

### `query_plans.py`
Ejecuta `EXPLAIN QUERY PLAN` sobre cada consulta `@Query` de Room contra los esquemas exportados para detectar escaneos completos de tabla (*full table scans*):
```bash
python tools/audit/query_plans.py
```

### `ficha_sample.py` y `ficha_keys.py`
- `ficha_keys.py <URL_O_HTML> [MARCA]`: Inspecciona las claves JSON reales enviadas por fuentes `jsData` (ej. animeav1) en la ficha de una serie.
- `ficha_sample.py <BASE> <N> <URL_LISTADO> ...`: Muestrea $N$ fichas reales para resumir diversidad de campos (estado, categorías) antes de definir un scraper.

### `format_search.py`
Evalúa si las búsquedas en AniList filtradas por formato (`movie`, `ova`, `special`) resuelven correctamente entradas que antes chocaban con series de TV:
```bash
python tools/audit/format_search.py <LINKS.json.gz> <FICHAS.jsonl.gz>
```

---

## 8. Edición, Formato y Utilidades de Desarrollo

### `edit.py`
Motor de edición por lotes determinista. Respeta los finales de línea exactos de cada fichero (CRLF o LF) y garantiza codificación UTF-8:
```bash
python tools/edit.py cambios.json [--dry-run]
```
- Exige que cada bloque de texto a reemplazar aparezca **exactamente una vez** (o el número especificado), abortando la operación completa si hay discrepancias para evitar daños silenciosos.

### `backslash.py`
Protección contra problemas de escape de barras invertidas y normalización de saltos de línea:
```bash
python tools/backslash.py put FICHERO        # Escribe cambiando el marcador {BS} por \
python tools/backslash.py check FICHERO      # Busca barras invertidas rotas
python tools/backslash.py eol check          # Valida consistencia LF/CRLF frente a git HEAD
python tools/backslash.py eol fix            # Restaura finales de línea según el repositorio
```

### `audit-diff.js`
Prepara el archivo de revisión para los agentes o revisores humanos:
```bash
node tools/audit-diff.js <SHA> [--prev <SHA_ANTERIOR>]
```
- Genera `build/audit-<SHA8>.diff` con todos los cambios y ficheros nuevos añadidos.
- Con `--prev`, crea `build/audit-delta-...txt` mostrando únicamente lo modificado respecto a la revisión previa.

### `test-count.js`
Extrae el cómputo exacto de la última ejecución de pruebas unitarias:
```bash
node tools/test-count.js
# Salida: pruebas=N saltadas=N fallos=N
```

### `vscode-java.js`
Valida la configuración del JDK en Visual Studio Code:
```bash
node tools/vscode-java.js [--fix]
```
- Detecta rutas rotas de runtime de Java tras actualizaciones de Eclipse Adoptium y las repara automáticamente.

### `vscode-extensions.ps1`
Quita de este VS Code las extensiones que Kaizen no usa (`fwcd.kotlin`, el paquete de Java menos Gradle, Maven y `android-dev-ext`). Por defecto solo hace un ensayo:
```powershell
pwsh tools/vscode-extensions.ps1                    # ensayo: enseña qué quitaría
pwsh tools/vscode-extensions.ps1 -Apply             # desinstala
pwsh tools/vscode-extensions.ps1 -Apply -JavaServer # además redhat.java
```
- Nunca toca `anthropic.claude-code` ni `google.google-antigravity`. Es un ajuste de usuario de VS Code (no del repositorio) y se revierte con `code --install-extension <id>`.

### `make_brand.py`
Genera el conjunto completo de iconos adaptativos, banners de Android TV (16:9) y recursos gráficos a partir de las imágenes maestras en `art/`:
```bash
python tools/make_brand.py
```

---

## 9. Conector ADB (`tools/adb-device/`)

- **Configuración**: `.env` (no versionado, plantilla en `.env.example`):
  ```env
  ADB_DEVICE=<IP_SHIELD>:5555
  ADB_PACKAGE=app.kaizen
  ```
- **Validación de conexión**:
  - Windows: `.\tools\adb-device\test_connection.ps1`
  - Linux/macOS: `bash tools/adb-device/test_connection.sh`
