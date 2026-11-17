# KAIZEN

Aplicación de anime para Android TV (probada en un tvbox). Reúne varias fuentes en una sola interfaz con el mando a distancia: animeav1, animegratis y jkanime.

## Qué hace

- Catálogo interno que recorre cada fuente cada 14 días y une las series repetidas entre fuentes.
- Notas ponderadas de todas las fuentes activas, y recientes ordenados por fecha.
- Buscar (texto, géneros y orden), Mejores en general, Inicio y Emisión.
- Reproductor con las teclas multimedia del mando.
- Copias de seguridad en una carpeta de red SMB (un NAS) para pasar favoritos, historial y ajustes de una tele a otra, con asistente para elegir la carpeta.
- Estados de serie (Viendo, Completada, Pendiente, Descartada), sección «Mi lista», y orden y filtro por estado en un panel lateral oculto de cada lista.
- Filas «Continuar viendo» y «Capítulos recientes» en la pantalla de inicio de Android TV, búsqueda tolerante a acentos y vocales largas, ficha con botones reorganizados, menú lateral ordenable (también «Fuentes») y estadísticas ampliadas.
- El reproductor suelta la pantalla en pausa (a los 10 min se cierra, para el protector de pantalla), el contador de vistos y el corazón de favorito de las tarjetas siguen el mismo criterio que la ficha, y el panel lateral filtra por «Solo favoritas» y por capítulos vistos o no vistos en «Recientes».
- Bypass de Cloudflare con el mismo protocolo de pausas que las fuentes, ventana con las fases al cargar un capítulo, reproductor con las proporciones de SmartTube, tamaños y colores unificados, Configuración reorganizada (con pantallas de tarjetas para ordenar botones y secciones) y el análisis de los cuelgues por disco lento.

## Compilar

Requiere JDK 17 y el SDK de Android. El tipo de compilación se llama `tv`.

```
./gradlew :app:assembleTv
./gradlew :app:testDebugUnitTest
```

`tools/build-kaizen.sh` (Git Bash) compila, firma, instala en el dispositivo por ADB y comprueba el arranque.

## Licencia

MIT. Ver `LICENSE`. El código de UKIKU en el que se basa este proyecto, lo que KAIZEN toma de SmartTube y los agradecimientos a todos los proyectos y personas de los que depende están en `THIRD_PARTY.md`.
