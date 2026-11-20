---
title: Base de conocimiento
type: index
tags:
  - rsc
  - memory
  - android-tv
  - kaizen
updated: 2026-11-19
---

# Base de conocimiento de Kaizen (`docs/wiki`)

Bienvenido a la memoria persistente del proyecto **Kaizen** gestionada a través de [rsc-harness](https://ericrisco.github.io/rsc-harness/). Este wiki proporciona contexto continuo y estructurado entre sesiones de desarrollo para agentes y desarrolladores.

Cómo está ordenada: la **guía** dice cómo es Kaizen y qué reglas sigue; la **referencia** profundiza en un tema; la **operación** recoge lo que se hace y lo que queda; el **registro** guarda la historia. Cada nota termina con una sección «Relacionado» que enlaza con sus vecinas.

## 1. Guía: cómo es Kaizen

- **[Mapa de arquitectura](architecture.md)**: cómo funciona y por qué, por concepto.
  - Módulo `:app`, el flujo de la web a la pantalla y los subsistemas (fuentes, catálogo, emparejado con AniList, bases de datos, copias y sincronización, reproducción, interfaz de TV y segundo plano).
  - Sincronización multi-dispositivo por WebDAV o carpeta de red SMB (`commons/sync/`), convergencia con lápidas y uniones con AniList que viajan con ella (`AnilistShare`).
  - Gestión de bases de datos (`CatalogDB`, `UserDB`, `CacheDB`) y copias portables (`CatalogSeed`, `SettingsBackup`).
  - Ciclo del reproductor multimedia (AFR, Media3, AniSkip con validación de duración).
- **[Mapa del código](../CODEBASE-MAP.md)**: dónde está cada cosa. Puntos de entrada, paquetes, ficheros clave, actividades, intents de ADB y receptores, herramientas y cómo comprobar un cambio.
- **[Stack tecnológico](stack.md)**: versiones de Kotlin (`2.4.21`), Java (JDK 17 Adoptium), AGP (`9.4.1`) y KSP; variantes de compilación (tipo `tv` con ABI `arm64-v8a`); entorno de prueba en hardware real (tvbox).
- **[Constitución de ingeniería](sdd/constitution.md)**: las reglas no negociables.
  - Estándares obligatorios de plataforma (Android TV / Leanback).
  - Canons de navegación por D-pad, rendimiento de arranque y ciclo de catálogo.
  - Guardas contra ANRs en volúmenes ampliados / disco lento (instalación obligatoria con `-f`).
  - Pautas de depuración del APK y bypass de Cloudflare.

## 2. Referencia: un tema a fondo

- **[Emparejado con AniList](emparejado-anilist.md)**: decisiones, criterios y límites del emparejado.
- **[Esquema de la ficha de una web](webficha-esquema.md)**: la forma única que rellenan todas las fuentes (`WebFicha`).
- **[Control por ADB](control-por-adb.md)**: las veinticuatro acciones del receptor, qué leen y qué escriben.
- **[Lecciones de octubre](lecciones-de-octubre.md)**: causas y trampas ya resueltas, de antes de que empezara el registro.

## 3. Operación: lo que se hace y lo que queda

- **[Pendientes](pendientes.md)**: lo que queda por hacer, comprobar o decidir (tablas, menú, copias, repositorio).
- **[Instalar Kaizen en otro equipo](instalar-en-otro-equipo.md)**: guion paso a paso para instalar la release del APK.

## 4. Registro: la historia

- **[Bitácora de actividad](log.md)**: historial cronológico de cambios, descubrimientos y sesiones de depuración.

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
