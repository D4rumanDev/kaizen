---
title: Lecciones de octubre
type: reference
tags:
  - kaizen
  - lecciones
  - historial
updated: 2026-11-19
---

# Lecciones de octubre de 2026: causas y trampas ya resueltas

Lo duradero de las notas de sesión del 23 al 29 de octubre (`AUDITORIA-2026-10-12`, `CAMBIOS-2026-10-27/28/29`, `ESTADOS-ORDEN-Y-FICHAS`, `INICIO-ANDROID-TV-FICHA-Y-BUSQUEDA` y `PANTALLA-CONTADOR-CORAZON-Y-FILTROS`), que se retiraron de la wiki. El [log.md](log.md) empieza en noviembre, así que esto cubre lo anterior.

**Cómo leerlo**: son causas y reglas que ya se aplicaron. No se han vuelto a comprobar contra el código actual y los nombres de clases o ficheros pueden haber cambiado; si algo difiere, manda el código. El texto está saneado (sin nombres de equipos, de usuario ni direcciones). Las notas completas, con commits y pruebas, siguen en la copia local `backups/docs-20261119-antes-orden/bm/` y en el historial antiguo de git (rama local `archivo-historial-2026-11-17`).

## Red y carga

- **Sin tiempo de espera, la pantalla se queda «intentándolo»** (27-oct). En TV el tiempo de espera por defecto era `0`, que en jsoup es **sin límite**: `BypassUtil.isNeeded()` y las demás comprobaciones de Cloudflare podían colgarse para siempre. Causa probable, deducida del código y no reproducida. Además `launchBypass` se protegía con `isLoading`, que solo liberaba `TVMain.onResume`.
- **Imagen del capítulo: «a veces carga y a veces no»** (27-oct). Coil obedecía las cabeceras del servidor (`no-cache`) y, con la web caída, no mostraba ni la imagen ya guardada. Regla: `CacheFirstStrategy` usa una imagen guardada en disco (2xx, `Content-Type: image/*`, menos de 30 días) sin preguntar a la web.

## Interfaz y foco

- **Trampa de Kotlin** (27-oct): un valor global que depende de otro declarado **después** en el mismo archivo vale cero, sin error de compilación (pasó con `PlayerIcon`).
- **Foco tras quitar una tarjeta** (27-oct). Al desaparecer la tarjeta enfocada, el foco caía en el menú lateral y, a los 250 ms (`FOCUS_SELECT_DELAY_MS`), el menú cambiaba de sección. Arreglo: `GridFocus` + `refocusTarget`; tras una acción del menú de una tarjeta hay una ventana de 3 s (`CardMenuSignal`).
- **Pantalla en pausa** (25-oct). `TVPlayer.onCreate` ponía `FLAG_KEEP_SCREEN_ON` y nunca lo quitaba, así que con el vídeo en pausa la tele no entraba en protector de pantalla y un fotograma fijo puede quemar el panel.
- **Contador y «Siguiente» de la ficha** (25-oct). Contaban lo visto solo por `eid`, mientras que el ojo de cada capítulo usa `chapterIsSeenFlow` (mismo `eid`, o mismo número de la misma serie en otra fuente): salía «1/4» con ojos en varios capítulos. Ahora usan el mismo SQL que el ojo.
- **Corazón de favorito** (25-oct). Las tarjetas decidían por título normalizado y el botón de la ficha por `FavoriteAV1Dao.isFav` (mismo `aid` o mismo id de serie). Límite heredado: el `aid` no lleva la fuente; si dos fuentes reutilizaran un `aid` para series distintas, saldría un corazón de más.
- **Reglas de ordenación** (23-oct, `sortedItems`): pulsar el criterio elegido invierte el sentido; lo que no trae el dato va siempre al final; lo que no es una serie (avisos, tarjetas de reintento) no se mueve. El estado ordena así: Viendo, Pendiente, Completada, Dropeada, Ninguna.

## Copias y datos del usuario

- **La copia buena no debe caducar tras una pérdida** (28-oct). `KaizenBackup.best()` es la copia más completa (la de más tamaño) y `expired()` no borra nunca la más reciente, la más completa ni la más completa desde la última aceptación. «Restaurar la copia más completa» sustituyó a «la última», que tras una pérdida sale vacía.
- **Pérdida de datos del 27-oct: causa sin verificar** (28-oct). La sospecha principal fue un `pm move-package … internal` cuya hora cae dentro de la ventana, pero no cuadra del todo (movería también las preferencias) y no se encontró en el código nada que borre las tablas del usuario (`CacheCleaner` no las toca). El registro de depuración anota los recuentos en cada apertura para localizarla si vuelve a pasar.
- **Código que no se pudo probar se elimina** (28-oct): `Callback.onOpen` y `UserDataMigration` se quitaron en vez de arreglarlos sin poder probarlos, porque la migración ya se había ejecutado.
- **El catálogo no va en las copias** (se rehace). Caduca así: cada 14 días se pide a cada fuente su total y cada 30 se recorre entera, aunque no cambie, para notas, votos y años (`SourceState`).
- **La exportación de reglas perdía uniones** (29-oct). `UserTitles.export()` omitía las reglas cuyo id se había unido a otra serie: con una copia real, 1.082 de 1.640 ids de reglas salían sin fichas y casi todas las uniones y separaciones se habrían perdido al restaurar en otro equipo. Ahora llevan las fichas de la serie vigente.
- **Repartir ids con el libro de series vacío** (29-oct). La primera pasada de auditoría se rechazó: `writeCatalog` y la restauración podían repartir ids desde 1 antes de copiar el libro antiguo, y habrían chocado con los ids que nombran las reglas del usuario (711 uniones, 215 separaciones). Corregido con `ensureIds` (carga o falla antes de repartir).
- **Comparador inestable** (29-oct): `IllegalArgumentException: Comparison method violates its general contract!` en `RecentsArchive.newestFirst`. El comparador leía claves de identidad que dependen de `SeriesLedger` (otro hilo puede cargarlo o cambiarlo) dentro de la ordenación; ahora se leen una vez por entrada.
- **Límites conocidos al separar una ficha** (29-oct): sus vistos van con ella, pero el progreso se queda en la serie original; y como una fila guarda una sola ficha, separar esa ficha deja a las demás sin el favorito.

## Plataforma Android y compilación

- **No perfilar con `am start --start-profiler`** (24-oct). La compilación no es depurable ni *profileable* por shell, lanza `SecurityException` y puede tumbar `system_server` (hubo que reiniciar el equipo). Para medir se usan marcas `Log` con `SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()`; entonces salió primer dibujo a ~1,1 s y portadas a los 3 s.
- **Windows y finales de línea** (24-oct): un script que reescribe un `.kt` cambia los finales de línea. Se comprueba con `git diff --stat` y se normaliza o se edita con la herramienta de edición.
- **Enlace `kaizen://tv/series?url=…`** (24-oct). Lo atiende `TVMain` y abre la ficha. Solo acepta URLs `https` cuyo host sea el de una fuente o un subdominio suyo (`isSourceUrl`), porque el filtro es `BROWSABLE` y cualquier app o web puede lanzarlo.
- **Sin `launchMode="singleTask"` en `TVMain`** (24-oct): cerraría el reproductor y la ficha al volver desde el icono o la notificación. Los intents de las filas llevan `NEW_TASK | CLEAR_TOP | SINGLE_TOP`.
- **Logotipo del canal de inicio** (24-oct): con un `LOGO_VERSION` distinto del guardado se actualiza el canal ya creado con `updatePreviewChannel`; borrarlo y recrearlo perdería su visibilidad en inicio.
- **Conscrypt se mantiene** (12-oct). `App.kt` llama a `AllSSLOkHttpClient.enableTLS()`, que lo usa para dar TLS 1.3 en Android 8 y 9 (`minSdk` 26; el TLS 1.3 nativo llega con Android 10). Quitarlo ahorraría 4,5 MB a costa de esos equipos.
- **ABIs: dato desfasado**. El 12-oct se quitó `armeabi-v7a` (APK de 19,0 MB, sin instalación en equipos solo de 32 bits). Hoy `app/build.gradle` vuelve a declarar `abiFilters 'arm64-v8a', 'armeabi-v7a'` y el log del 17-nov recoge la instalación en una TV de 32 bits: manda el `build.gradle`.

## Cómo se trabajó

- **Cada instalación se comprobó igual** (29-oct): copia previa verificada con sha256, unos 45 s sin `FATAL EXCEPTION` y copia posterior idéntica campo a campo (favoritos, vistos, progreso, estados y reglas).
- **Auditorías con un subagente nuevo en cada pasada**, y la primera pasada rechazó cambios que luego se corrigieron (ver «Repartir ids con el libro de series vacío»).
- **Pruebas instrumentadas de `Record`: en un emulador, nunca en la TV** (28-oct).
- **Teclear en pantalla puede llegar a otra aplicación** (29-oct). Al intentar activar «Control por ADB» desde la pantalla, las teclas llegaron al lanzador del equipo en vez de a Kaizen y cambiaron ajustes suyos. Desde entonces «Control por ADB» lo activa el usuario a mano.

## Relacionado

- [architecture.md](architecture.md): el diseño actual (§3 E copias y sincronización, §3 F reproducción, §3 G interfaz).
- [log.md](log.md): lo que se hizo desde noviembre.
- [Constitución de ingeniería](sdd/constitution.md): las reglas de ingeniería vigentes.
