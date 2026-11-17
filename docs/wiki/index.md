---
title: Kaizen Knowledge Base
type: index
tags:
  - rsc
  - memory
  - android-tv
  - kaizen
updated: 2026-11-14
---

# Kaizen Knowledge Base (`docs/wiki`)

Bienvenido a la memoria persistente del proyecto **Kaizen** gestionada a través de [rsc-harness](https://ericrisco.github.io/rsc-harness/). Este wiki proporciona contexto continuo y estructurado entre sesiones de desarrollo para agentes y desarrolladores.

## Estructura de Memoria

   - Estándares obligatorios de plataforma (Android TV / Leanback).
   - Canons de navegación por D-pad, rendimiento de arranque y ciclo de catálogo.
   - Guardas contra ANRs en volúmenes ampliados / disco lento (instalación obligatoria con `-f`).
   - Pautas de depuración del APK y bypass de Cloudflare.

2. **[Arquitectura y Módulos](architecture.md)**
   - Visión general del módulo `:app`, el flujo de la web a la pantalla y los subsistemas.
   - Sincronización multi-dispositivo por WebDAV o carpeta de red SMB (`commons/sync/`), convergencia con lápidas y uniones con AniList que viajan con ella (`AnilistShare`).
   - Flujo de peticiones de scraping concurrente (`animeav1`, `animegratis`, `jkanime`, etc.).
   - Gestión de bases de datos (`CatalogDB`, `UserDB`, `CacheDB`) y copias portables (`CatalogSeed`, `SettingsBackup`).
   - Ciclo del reproductor multimedia (AFR, Media3, AniSkip con validación de duración).

3. **[Stack Tecnológico Canónico](stack.md)**
   - Versiones de Kotlin (`2.4.21`), Java (JDK 17 Adoptium), AGP (`9.4.1`) y KSP.
   - Variantes de compilación (especial atención al tipo `tv` con ABI `arm64-v8a`).
   - Entorno de prueba en hardware real (tvbox).

4. **[Bitácora de Sesiones y Decisiones (`log.md`)](log.md)**
   - Historial cronológico de cambios, descubrimientos y sesiones de depuración.

   - Lo que queda por hacer, comprobar o decidir (tablas, menú, copias, repositorio).

6. **Referencia** (todo en la wiki):
   - [Emparejado con AniList](emparejado-anilist.md): decisiones, criterios y límites del emparejado.
   - [Esquema de la ficha de una web](webficha-esquema.md): la forma única que rellenan todas las fuentes (`WebFicha`).

7. **[Mapa Estructural del Código](../CODEBASE-MAP.md)**
   - Guía de inicio rápido para ubicar puntos de entrada, actividades, intents de ADB y receptores.

## Herramientas del Proyecto (`tools/`)

Ver el manual completo en [Herramientas de Kaizen (`tools/README.md`)](../../tools/README.md).

- **[tv.js](../../tools/README.md#1-control-y-despliegue-en-android-tv-tvjs)**: Control directo del tvbox por ADB (instalación segura con `-r -f`, arranque, recolección de `debug.jsonl` y logcat con `logs`, modo `awake`, informes `health`, copias `backup` y consultas por ADB).
- **[ship.js](../../tools/README.md#2-publicación-y-auditoría-de-código-shipjs)**: Ciclo formal de publicación (`check` para seguridad y pruebas, `publish` verificado contra SHA auditado, `docs` para cierre de documentación).
- **[bench.js](../../tools/README.md#3-medición-de-rendimiento-y-arranque-benchjs)**: Medición científica y repetible de tiempos de arranque en frío y consumo de CPU en la TV.
- **[make-catalog-seed.py](../../tools/README.md#make-catalog-seedpy) / [make-links-seed.py](../../tools/README.md#make-links-seedpy)**: Generación y firma ECDSA P-256 de semillas portables del catálogo unificado y de enlaces con AniList.
- **[sign-sources.sh](../../tools/README.md#sign-sourcessh)**: Firma criptográfica del manifiesto `index.json` de fuentes.
- **[probe_servers.py](../../tools/README.md#probe_serverspy) / [ficha-preview.sh](../../tools/README.md#ficha-previewsh)**: Pruebas de vídeo y previsualización de scraping desde el PC sin instalar APK.
- **[retrace.js](../../tools/README.md#retracejs)**: Desofuscación automática de pilas de error minificadas con R8.
- **[Suite de Auditorías (`tools/audit/`)](../../tools/README.md#7-suite-de-auditorías-toolsaudit)**: Orquestador `audit.py` (uniones, slugs, match, pending, unmerge, check-migration) y analizador estático `code_graph.py` (código muerto y grafo de dependencias).
- **[ADB Device](../../tools/adb-device/README.md)**: Conexión TCP/IP y scripts de prueba para Android TV.
- **[Basic Memory MCP](../../tools/basic-memory/README.md)**: Réplica continua de documentación hacia servidor MCP en `<IP_BM>:8765`.
