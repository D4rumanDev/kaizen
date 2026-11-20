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

Por qué `strict()`: con el modo permisivo activo para arreglar un servidor de vídeo, un intermediario podría falsificar las respuestas de AniList y con ellas los enlaces y las fichas guardadas. `strict()` comparte dispatcher y pool con el cliente base (así `AppHttpClient.shutdown()` lo corta también) pero no su fábrica de sockets: una conexión abierta con la política permisiva no se reutiliza. Las imágenes siguen con la política permisiva a propósito: no llevan datos sensibles y algunas fuentes tienen certificados rotos.

No revisado para esta página: `JsExtractor`, `WebDavStore` y `WarpTunnel`, que construyen su propio cliente.

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
