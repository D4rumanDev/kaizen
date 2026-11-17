# Software de terceros

KAIZEN parte de UKIKU (v238), proyecto de código abierto de Jordy Alexis Mendoza Caballero, publicado con licencia MIT. Parte sustancial del código de esta aplicación procede de ese proyecto. El aviso de copyright y la licencia originales se conservan aquí, como exige la licencia.

## UKIKU

MIT License

Copyright (c) 2018 Jordy Alexis Mendoza Caballero

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Rhino

Motor de JavaScript de Mozilla (`org.mozilla:rhino`), usado sin modificar como dependencia. Licencia MPL-2.0: <https://github.com/mozilla/rhino>

## NextLib

Decodificadores FFmpeg para Media3 (`io.github.anilbeesetti:nextlib-media3ext`), usados como dependencia. Licencia **GPL-3.0**: <https://github.com/anilbeesetti/nextlib>

Mientras KAIZEN no se distribuya (repositorio privado, uso personal) esto no impone nada. Si algún día se publica el APK o el código, el conjunto debe licenciarse como GPL-3.0 (compatible con la MIT de UKIKU, cuyo aviso se conserva arriba) o sustituirse NextLib. No hay sustituto permisivo en Maven para decodificar H.264 de 10 bits por software: la extensión FFmpeg de Media3 es solo de audio y hay que compilarla a mano, y la de dav1d solo decodifica AV1.

## Extractores de vídeo y reto de Cloudflare (Aniyomi / Kohi-den)

Los servidores `VidHide`, `Filemoon`, `Uqload`, `MixDrop`, `Savefile`, `VidMoly`, `Sendvid`, `Sibnet`, `StreamHub`, `Doodstream` y `VidGuard` (`app/src/main/java/app/kaizen/videoservers/`) y `CloudflareInterceptor` (`app/src/main/java/app/kaizen/source/`) se basan en los extractores y el interceptor de `lib/` de <https://github.com/Kohi-den/extensions-source>, continuación de <https://github.com/aniyomiorg/aniyomi-extensions>. Licencia **Apache 2.0** (<https://www.apache.org/licenses/LICENSE-2.0>). Están reescritos sobre las clases de KAIZEN (`SourceHttp`, `HostGate`, `Unpacker`, `Option`): no se copia su código tal cual y se han cambiado el reparto de responsabilidades, las cabeceras y el almacenamiento del resultado del reto.

## SmartTube

KAIZEN toma de SmartTube (<https://github.com/yuliskov/SmartTube>, de yuliskov) mucho más que un icono. Lo que sigue es diseño e ideas de SmartTube que KAIZEN reimplementa con su propio código (Compose para TV; no se copia código de SmartTube), y que sin ese trabajo no existiría tal como es:

- **Estructura de Configuración**: la rejilla de categorías con icono y el panel lateral derecho con las opciones (casillas, listas de elección, submenús, confirmaciones), con la miga de pan y el foco que vuelve a la categoría de la que se salió. El reparto en Reproductor, Pantalla, Fuentes y red, Copia de seguridad, Acerca de y Depuración sigue su organización.
- **Pantalla principal**: el menú lateral de secciones que se abre al enfocarlo, el comportamiento de «Atrás» (en el contenido abre el menú; en el menú, la primera pulsación avisa y la segunda sale) y el orden y ocultación de las secciones.
- **Reproductor**: las proporciones de los botones (círculo de 48 dp con icono de 32 dp), los botones configurables y reordenables, la escala del vídeo, el panel lateral de pistas, la fila de capítulos bajo los botones y el ajuste automático de la frecuencia de pantalla (AFR).
- **Menús de tarjeta**: el menú de pulsación larga sobre una serie o un capítulo.
- **Tarjetas de ajustes**: la tarjeta de icono y texto de las categorías.

KAIZEN no copia ningún recurso ni código de SmartTube: el icono del botón de escala del vídeo, que antes venía de allí, se sustituyó por el de Material Icons (ver más abajo). SmartTube se publica con licencia MIT.

## Iconos de Material

Varios iconos del menú, de las barras y del reproductor (por ejemplo `header_ic_record`, el reloj con flecha del historial, y `ic_video_scale`, la escala del vídeo) parten de Material Icons de Google, con licencia Apache 2.0: <https://github.com/google/material-design-icons>.

## Servicios de terceros

KAIZEN consulta, sin incluir su código, la API pública de AniList (<https://anilist.co>) para emparejar series y completar fichas, y la API de AniSkip (<https://api.aniskip.com>) para los saltos de opening y ending. Las series y los vídeos vienen de las webs de anime que el usuario activa como fuente; su contenido es de sus propietarios.

## jcifs (cliente SMB)

Cliente SMB 2/3 en Java (`org.codelibs:jcifs`, continuación de jcifs-ng), usado como dependencia para guardar las copias de seguridad en una carpeta de red (un NAS). Licencia **LGPL-2.1**: <https://github.com/codelibs/jcifs>. Trae como dependencias BouncyCastle (`bcprov`, licencia MIT: <https://www.bouncycastle.org/licence.html>) y SLF4J (MIT: <https://www.slf4j.org/license.html>).

## Otras dependencias

Apache 2.0: AndroidX, Media3, OkHttp, Gson, Coil, Commons Text, Kotlin y kotlinx.coroutines. BSD de 3 cláusulas: NanoHTTPD. MIT: jsoup.

## Agradecimientos

Gracias a todos los que hacen posible este proyecto:

- **Jordy Alexis Mendoza Caballero** (UKIKU), por la base sobre la que nació KAIZEN.
- **yuliskov** y la comunidad de **SmartTube**, por una forma de usar la tele con el mando que KAIZEN ha tomado como modelo.
- **Kohi-den** y la comunidad de **Aniyomi**, por los extractores de vídeo y el interceptor del reto de Cloudflare en los que se basan los de KAIZEN.
- **anilbeesetti** (NextLib), **Mozilla** (Rhino), **jsoup**, **NanoHTTPD**, **Coil**, **OkHttp**, **Gson**, **Apache Commons**, **JetBrains** (Kotlin) y los equipos de **AndroidX**, **Compose** y **Media3**, por las bibliotecas que sostienen la aplicación.
- **AniList** y **AniSkip**, por sus servicios abiertos.
- **Google** (Material Icons) y todos los que mantienen proyectos abiertos y gratuitos.
- A las comunidades de anime que mantienen las webs de las que salen las series.
