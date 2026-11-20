---
title: Red: DNS, TLS y proxy local
type: reference
tags:
  - kaizen
  - red
  - tls
  - dns
updated: 2026-11-20
---

# Red: DNS, TLS y proxy local

Qué hace la app con la red y dónde están sus límites. Los ajustes se cambian en Configuración › Servidores y red. Esta página dice lo que se comprobó en el código el 20-nov-2026; lo que no se revisó va marcado.

## Qué cliente valida qué

La validación TLS la decide [`TlsPolicy`](../../app/src/main/java/app/kaizen/commons/TlsPolicy.kt): la del sistema por defecto y, si el usuario activa «permisiva» (solo en memoria, hasta cerrar la app), acepta cualquier certificado.

| Cliente | Política | Para qué |
|---|---|---|
| `AppHttpClient.streaming()` | `TlsPolicy` (la permisiva les afecta) | Fuentes por scraping (`SourceHttp`), el proxy local y las imágenes de la app |
| `AppHttpClient.get()` | `TlsPolicy` | Variante con caché en disco; hoy no la usa nadie en `src/main` |
| `AppHttpClient.strict()` | Sistema, siempre | AniList y AniSkip: sus respuestas se guardan y no deben poder falsearse |
| Cliente de `LaLigaBlock` | Sistema, siempre | Servicio de un tercero |
| `AppHttpClient.strict()` en `UpdateChecker` y `UpdateFlow` | Sistema, siempre | Aviso y descarga de versiones nuevas (GitHub); ver más abajo |

Por qué `strict()`: con el modo permisivo activo para arreglar un servidor de vídeo, un intermediario podría falsificar las respuestas de AniList y con ellas los enlaces y las fichas guardadas. `strict()` comparte dispatcher y pool con el cliente base (así `AppHttpClient.shutdown()` lo corta también) pero no su fábrica de sockets: una conexión abierta con la política permisiva no se reutiliza. Las imágenes siguen con la política permisiva a propósito: no llevan datos sensibles y algunas fuentes tienen certificados rotos.

No revisado para esta página: `JsExtractor`, `WebDavStore` y `WarpTunnel`, que construyen su propio cliente.

## Aviso de versiones nuevas y actualización desde la app

[`UpdateChecker`](../../app/src/main/java/app/kaizen/commons/UpdateChecker.kt) mira la última release de `D4rumanDev/kaizen` (`api.github.com/repos/D4rumanDev/kaizen/releases/latest`). Si es más nueva que la instalada, muestra la etiqueta «Nueva versión X» en Inicio, junto a la hora; al pulsarla se abre un diálogo para **descargar e instalar**, dejarlo para más tarde u omitir esa versión.

- **Ajuste:** Configuración › Acerca de › Actualizaciones › «Avisar de versiones nuevas» (Avisar, de fábrica, o No mirar: sin peticiones ni etiqueta). «Comprobar ahora» mira aunque esté en «No mirar», no respeta el límite de 24 h y devuelve la etiqueta si se había omitido.
- **Cuándo mira:** 20 s después de abrir la app, en segundo plano, como mucho una vez cada 24 h (6 h tras un fallo). Nunca durante la reproducción.
- **Qué se acepta de la API:** solo etiquetas numéricas (`v6.3.0`, `6.3`); borradores, prereleases y cualquier otra etiqueta se ignoran. La dirección que se enseña sale de la etiqueta validada, no del `html_url` de la respuesta. El cuerpo se lee con tope de 256 KB.

### Descargar e instalar ([`UpdateFlow`](../../app/src/main/java/app/kaizen/commons/UpdateFlow.kt), [`UpdateInstaller`](../../app/src/main/java/app/kaizen/commons/UpdateInstaller.kt), [`UpdateInstall`](../../app/src/main/java/app/kaizen/commons/UpdateInstall.kt))

Falla cerrado: si cualquier comprobación falla, se borra el fichero y no se instala nada. En este orden:

1. **Permiso:** `REQUEST_INSTALL_PACKAGES` está declarado en el manifiesto de `tv` (antes se quitaba a propósito). Android pide una vez «instalar apps desconocidas» para Kaizen; si esa TV no tiene la pantalla, el diálogo lo dice y remite a la dirección de la release.
2. **Asset exacto:** `app-tv.apk` de la release avisada (no borrador ni prerelease), `state` «uploaded», tamaño entre 1 B y 60 MB, y `browser_download_url` igual a `https://github.com/D4rumanDev/kaizen/releases/download/<etiqueta>/app-tv.apk`.
3. **Huella obligatoria:** el `digest` `sha256:…` que publica la API de GitHub. **Sin `digest` no se instala.**
4. **Descarga:** cliente estricto (TLS siempre con el sistema), `FallbackDns`, sin redirecciones entre https y http (las de GitHub a `objects.githubusercontent.com` sí, son https), el tamaño recibido debe ser exactamente el anunciado y se corta si lo pasa. Va a `cacheDir/updates/app-tv.apk.part` y se renombra al terminar; se borra ante cualquier fallo o cancelación.
5. **SHA-256** del fichero igual al `digest`.
6. **Paquete:** `getPackageArchiveInfo` del fichero: mismo paquete, `versionCode` mayor que el instalado y **exactamente las mismas firmas** (SHA-256 de los certificados) que la app instalada. Si no se puede leer la firma instalada, no se instala.
7. **Instalación:** sesión de `PackageInstaller` (sin `FileProvider`). El sistema pide la confirmación final y vuelve a comprobar la firma. El resultado llega a `UpdateInstallReceiver` (no exportado).

- **Qué se guarda** (`kaizen_update_*`): cuándo se miró y se intentó, la etiqueta más nueva, la de la que ya se avisó y la omitida. Nada de esto entra en la copia de ajustes.
- **Privacidad:** las peticiones a `api.github.com` y `github.com` (y al host al que GitHub redirija la descarga) revelan la IP y el User-Agent de la app (`UserAgent.current()`); nada más, sin identificadores ni correo.
- **Límite de la defensa:** quien controle la release pero no la clave de firma no puede instalar nada (la huella la publica la misma cuenta, así que la comprobación de firma es la que protege). Quien tuviera la clave de firma sí podría. No hay revocación: una versión mala ya instalada solo se arregla publicando otra mayor.
- **Límite de GitHub:** la API sin credenciales admite 60 peticiones por hora y IP; con una al día no se acerca.

## DNS

[`FallbackDns`](../../app/src/main/java/app/kaizen/commons/FallbackDns.kt) resuelve con el DNS de la red y solo recurre al de relevo si con él no se consigue conectar con un servidor:

- El relevo es DNS sobre TLS (puerto 853, certificado comprobado). De fábrica Quad9; Cloudflare y Google son alternativas (`FallbackDns.Provider`).
- Un servidor pasado al relevo se resuelve así durante 10 minutos; las respuestas se recuerdan 5 minutos y una sin respuesta 30 segundos. Tope de 512 nombres.
- Solo acepta IPv4 públicas del relevo y descarta las demás. Solo consulta registros A.
- `noRelay` en una petición respeta el filtro de la red (p. ej. AdGuard): lo que bloquea falla.
- `diagnose` distingue «la red filtra el nombre» de «el servidor está caído», solo para avisar.

## Proxy local para reproductores externos

[`KaizenProxy`](../../app/src/main/java/app/kaizen/commons/KaizenProxy.kt) escucha solo en `127.0.0.1` (puertos 6992 a 7010), con un máximo de 16 sesiones, identificadas por un UUID y caducadas a las 6 horas sin uso. Cada sesión solo puede pedir los hosts de su vídeo y los que aparezcan en sus listas o redirecciones.

Desde el 20-nov-2026 no llega a direcciones privadas ([`PrivateAddress`](../../app/src/main/java/app/kaizen/commons/PrivateAddress.kt)): el propio equipo, `0/8`, `10/8`, `172.16/12`, `192.168/16`, enlace local (`169.254/16`, `fe80::/10`), `100.64/10` (CGNAT), `198.18/15` (pruebas de red), `fc00::/7`, multicast, difusión e IPv4 mapeada en IPv6. La comprobación está en el socket (`GuardedSocketFactory`), en el momento de conectar, para cubrir nombres resueltos, IP literales (OkHttp no consulta el DNS con ellas) y los saltos de una redirección. Para que ningún camino se la salte, el cliente del proxy no usa el proxy del sistema (un SOCKS crea su propio socket) y tiene su propio pool de conexiones (no reutiliza una abierta por otro cliente sin guardián). También resuelve con `FallbackDns`. Un destino bloqueado así no cuenta como servidor caído en `HostGate`.

## Límites conocidos

- El relevo DNS no consulta registros AAAA: en una red solo IPv6 o con NAT64 no daría nada útil. Añadirlos tiene el riesgo de esperas largas con un IPv6 roto.
- El filtro de direcciones privadas no cubre los prefijos que traducen IPv4 dentro de IPv6 (NAT64, `64:ff9b::/96`).
- No hay fijación de certificados.
- La clave privada de WARP (cuenta gratuita anónima) está en las preferencias de la app (`KaizenStore`), sin cifrar; la protegen el sandbox de Android y `allowBackup=false`.
- Cleartext solo para `127.0.0.1`, `localhost`, `streamtape.com`, `tapecontent.net` y `mediafire.com` (`network_security.xml`).
