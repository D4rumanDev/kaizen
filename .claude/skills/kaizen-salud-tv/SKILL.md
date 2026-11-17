---
name: kaizen-salud-tv
description: Informe de salud del tvbox (registros, errores por web, recuentos de la app, última pasada de AniList, arranque) frente al informe anterior. Usar cuando el usuario pide revisar la TV, "cómo va", los registros, los pendientes de Fuentes o Comprobaciones, o antes de dar por cerrada una comprobación en la TV.
---

Solo lectura. Ejecuta desde la raíz del repo:

    node tools/tv.js health            # baja registros y estado por ADB
    node tools/tv.js health --no-logs  # reutiliza la última carpeta de backups/tv-logs

Resume al usuario solo lo que cambió respecto al informe anterior (`backups/health/`). «Arranque: filas de la pantalla de inicio» no es un coste (Rendimiento 4). Requiere «Control por ADB» activo para los recuentos. Documentación: `tools/README.md`.
