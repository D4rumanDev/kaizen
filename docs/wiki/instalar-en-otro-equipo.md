---
title: Instalar Kaizen en otro equipo (release del APK)
type: note
tags:
  - kaizen
  - instalacion
  - release
updated: 2026-11-17
---

# Instalar Kaizen en otro equipo

Guion paso a paso. El APK se publica como **release** del repositorio público de GitHub (`D4rumanDev/kaizen`). Se puede instalar de dos formas: **desde el propio equipo** con una app de descargas (por ejemplo «Downloader»; opción C0) o **desde un PC con ADB** (opciones C1 a C6).

Notación: `<IP_NUEVO>` es la IP del equipo nuevo; `<VERSION>` es la versión (`v6.0.8`). Las IPs reales no se escriben en el repositorio.

## A. Publicar el release (en el PC de desarrollo)

1. **Árbol limpio y probado**: `node tools/ship.js check` (pruebas, compilación y búsqueda de datos que no deben subir). Anota el SHA que imprime.
2. **Auditoría independiente** del cambio (agente `auditor-bee`) con ese SHA. Sin auditoría no se publica.
3. **Compilar el APK firmado**: `./gradlew --no-daemon :app:assembleTv` → `app/build/outputs/apk/tv/app-tv.apk`. La firma solo sale si existen el almacén de claves y su fichero de contraseña (ver `docs/wiki/stack.md`, «Claves y firma»); si no, el APK queda sin firmar y **no sirve para actualizar** una instalación existente.
4. **Comprobar la firma y anotar el hash**:
   - `apksigner verify --print-certs app-tv.apk` → el `SHA-256` del certificado debe ser siempre el mismo (el de la instalación que ya tienes); si cambia, Android rechaza la actualización.
   - `sha256sum app-tv.apk` → se pone en las notas del release.
5. **Publicar los commits**: `node tools/ship.js push --audited <SHA>` (nunca `--force` salvo limpieza decidida y con copia previa; ver `log.md`).
6. **Crear el release**:

   ```bash
   gh release create <VERSION> app/build/outputs/apk/tv/app-tv.apk \
     -R D4rumanDev/kaizen --title "Kaizen <VERSION>" \
     --notes "APK firmado, solo arm64-v8a. SHA-256 del APK: <hash>"
   ```

7. **Verificar**: `gh release view <VERSION> -R D4rumanDev/kaizen` (el fichero está y pesa lo mismo que el local).

## B. Preparar el equipo nuevo

1. **Opciones de desarrollador**: Ajustes → Preferencias del dispositivo → Acerca de → pulsar «Compilación» 7 veces.
2. **Depuración por red**: Ajustes → Preferencias del dispositivo → Opciones de desarrollador → activar «Depuración USB» (o «Depuración de red/ADB por Wi-Fi»). Anota la IP (Ajustes → Red).
3. **Comprobar la arquitectura**: el APK solo lleva **arm64-v8a** (`ndk.abiFilters` en `app/build.gradle`). Con el equipo conectado: `adb -s <IP_NUEVO>:5555 shell getprop ro.product.cpu.abi`.
   - `arm64-v8a` → sigue.
   - `armeabi-v7a` (algunas TV y TV antiguas) → este APK **no arranca**; hay que compilar otro con ese filtro. Para y avisa.

## C0. Instalar desde el propio equipo (sin PC)

1. Instalar «Downloader» desde la tienda del equipo y permitirle «instalar apps desconocidas» (Ajustes → Seguridad y restricciones).
2. En Downloader, abrir la dirección `https://github.com/D4rumanDev/kaizen/releases/download/<VERSION>/app-tv.apk`, esperar la descarga y pulsar «Instalar».
3. Comprobar la versión en Configuración › Acerca de y seguir en D.

## C. Instalar desde un PC con ADB

1. **Bajar el APK** (sin sesión, el repositorio es público): `curl -L -o app-tv.apk https://github.com/D4rumanDev/kaizen/releases/download/<VERSION>/app-tv.apk` (o `gh release download <VERSION> -R D4rumanDev/kaizen -p app-tv.apk`), en una carpeta nueva y vacía.
2. **Comprobar** que el hash coincide con el de las notas (`sha256sum`) y la firma (`apksigner verify --print-certs`).
3. **Conectar**: `adb connect <IP_NUEVO>:5555` y aceptar el aviso en la pantalla del equipo («Permitir siempre»).
4. **Instalar** (siempre con `-f`, almacenamiento interno, para evitar ANR por disco USB lento):
   `adb -s <IP_NUEVO>:5555 install -r -f app-tv.apk`
5. **Abrir**: `adb -s <IP_NUEVO>:5555 shell monkey -p app.kaizen -c android.intent.category.LEANBACK_LAUNCHER 1`
6. **Mirar que no falla**: `adb -s <IP_NUEVO>:5555 shell "logcat -d | grep -E '(FATAL EXCEPTION|ANR in app.kaizen)'"` → no debe salir nada.

## D. Primer arranque y datos

1. **Fuentes y catálogo**: la app arranca con las fuentes incluidas. «Descargar del repositorio» (Configuración › Acerca de › Repositorio de descargas) está **apagado por defecto**: actívalo si quieres que baje fuentes y catálogo ya hechos de `kaizen-sources` (público). Si no, el catálogo se construye recorriendo las webs. En **Configuración › Biblioteca › Fuentes**, «Recorrer las fuentes que falten» para rellenar el catálogo (tarda varios minutos).
2. **Verificación de webs**: si una fuente pide verificación (animegratis.net), aparece una tarjeta «Verificar fuente» en Recientes (se abre `/horario`). Pulsar «Listo» al terminar.
3. **Traer tus datos** (favoritos, vistos, ajustes) del equipo anterior: en el equipo anterior, **Configuración › Copia de seguridad › Crear copia ahora**; en el nuevo, **Configuración › Copia de seguridad › Local** (restaurar) o **Remota** (WebDAV, si la tienes configurada). Restaurar **sustituye** los datos del equipo nuevo: hazlo en un equipo recién instalado.
4. **Ajustes que conviene mirar**: tamaño de la interfaz (Pantalla), servidor preferido (Fuentes y red), y activar «Control por ADB» (Depuración) solo si vas a usar `tools/tv.js`.

## E. Actualizar más adelante

Repetir A (compilar con la **misma firma**, subir `versionCode` en `app/build.gradle`) y C.4 (`install -r -f`): se conservan los datos. Si Android responde `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, la firma es distinta: no se instala encima sin desinstalar (y desinstalar **borra** los datos: haz antes la copia de D.3).

## Si algo falla

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | El equipo no es arm64 | Ver B.3 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Firma distinta de la instalada | No desinstalar sin copia; usar el APK firmado con la clave de siempre |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK sin firmar | Compilar con el almacén de claves disponible (A.3) |
| `adb: device unauthorized` | No se aceptó el aviso | Revocar autorizaciones de depuración en el equipo y repetir C.3 |
| La descarga da 404 | Versión o nombre de fichero mal escritos | Mirar la lista en `https://github.com/D4rumanDev/kaizen/releases` |
