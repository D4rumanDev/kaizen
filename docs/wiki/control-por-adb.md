---
title: Control por ADB: acciones disponibles
type: reference
tags:
  - kaizen
  - adb
  - depuracion
updated: 2026-11-20
---

# Control por ADB: acciones disponibles

`AdbControlReceiver` ([código](../../app/src/main/java/app/kaizen/control/AdbControlReceiver.kt)) atiende órdenes enviadas con `am broadcast` para hacer copias y comprobar el estado sin manejar la tele:

```bash
adb -s $ADB_DEVICE shell am broadcast -a app.kaizen.action.STATUS -n app.kaizen/.control.AdbControlReceiver
```

Esta página es la única lista de acciones; antes vivía como comentario del código. La mayoría tienen su atajo en `node tools/tv.js` (ver [tools/README.md](../../tools/README.md)).

## Límites a propósito

- Solo lo puede enviar quien tenga el permiso `android.permission.DUMP` (el shell de ADB; una app normal no lo tiene).
- Hay que activar «Control por ADB» en Configuración › Depuración (viene apagado).
- Hay veinticuatro acciones y ninguna más (la lista cerrada `ACTIONS` del receptor).
- El resultado vuelve en `data=` de `am broadcast` y se anota en el registro. Las respuestas que son datos van en JSON codificado en base64.

## Solo leen

| Acción | Qué da |
|---|---|
| `STATUS` | Recuentos de la app. |
| `STORE` | Los ficheros de preferencias con su clase y sus claves, sin valores. |
| `CALLS` | Peticiones reales a cada web en un día, separando el segundo plano. |
| `ERRORS` | Resumen de la sesión del gestor de errores. |
| `BYPASS` | Cómo va el bypass de Cloudflare. |
| `LALIGA` | Qué fuentes activas bloquea un partido. |
| `SETTINGS` | Los ajustes que se pueden tocar, con valor y opciones. |
| `SETTING_GET` | Un ajuste: `--es name n`. |
| `SOURCES` | Las fuentes con su estado. |
| `SOURCES_CHECK` | Mide las fuentes activas. |
| `FICHA_GET` | Lo guardado de las fichas de las webs (`WebFichaQuery`, el mismo código que usa la app). `--es name64 <nombre en base64 UTF-8>`, `--es path ruta`, `--ez resuelta true` para que el dato salga de AniList si la ficha está enlazada y, si no lo tiene, de la web. Devuelve en `data=` un JSON `[{fuente,slug,value,origen}]` en base64, con 20 fichas como mucho; si el resultado pasa de 200 000 caracteres da error y hay que pedir un campo concreto con `path`. |
| `WARP` | Estado del túnel. Con `--ez evaluate true`, aplica el modo (en ese caso también escribe). |

## Escriben

| Acción | Qué hace |
|---|---|
| `BACKUP` | Crea una copia, como «Crear copia». Solo borra lo que ya borra ese botón: las copias caducadas. |
| `EXPORT_FICHAS` | Copia las fichas de AniList de `catalog-db` (`AnimeFicha`) a `fichas-anilist-<fecha>.jsonl.gz` en la carpeta de copias. Solo lee la base y escribe un fichero nuevo. Responde al momento con el nombre y sigue en segundo plano. |
| `IMPORT` | `--es file nombre.json`: añade lo que falte de un fichero de la carpeta de copias, como «Restaurar». Solo añade, nunca borra. |
| `SPLIT` | `--es file nombre.json`, `--ez regroup false` para no rehacer el catálogo después: deshace una unión equivocada pasando cada grupo de fichas del fichero a una serie propia. No borra nada. |
| `WALK` | Lanza el recorrido del catálogo («Recorrer las fuentes que falten»). Responde al momento y sigue en segundo plano. |
| `FILL_FICHAS` | `--ei limit N` (hasta 200), `--es fuente id`: abre las fichas de las webs sin enlace con AniList (por revisar o sin resultado) que aún no tienen copia, repartidas entre las fuentes, a ritmo lento y con las pausas de `HostGate`. Solo escribe `WebFicha`. Responde al momento y sigue en segundo plano. |
| `ANILIST_AUTO` | Resuelve sin pantalla las series dudosas con AniList que son claras. Sin extras da cómo va. Con `--ez start true` empieza, pero por defecto solo cuenta y no escribe. Para guardar de verdad los enlaces claros: `--ez start true --ez apply true --ez confirm true` (la app hace antes una copia y reagrupa el catálogo). `--ei limit N` acota las series (1 a 5000, por defecto 5000). Sigue en segundo plano, una serie a la vez. |
| `SETTING_SET` | `--es name n --es value v`: cambia un ajuste de la lista de `SETTINGS`, validado contra sus opciones. No toca «Control por ADB» ni el estado interno. |
| `SOURCES` con `--es set id --ez on true\|false` | Activa o desactiva una fuente (`on` vale `true` si se omite). Siempre debe quedar al menos una activa. |
| `IMAGE_MISSES` | Cuenta las imágenes que no existen. Con `--ez clear true`, las olvida. |
| `CACHE_CLEAR`, `LOG_CLEAR`, `SETTINGS_RESET` | Hacen lo que sus botones de Configuración. Exigen `--ez confirm true`. |
