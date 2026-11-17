---
name: kaizen-informe-emparejado
description: Copia nueva de la TV por ADB (BACKUP), bajada verificada a backups/tv-<fecha> y audit.py pending con resumen de fichas por revisar. Usar cuando acaba un re-emparejado, tras subir AnilistMatcher.VERSION, o cuando el usuario pide cerrar el emparejado o medir las fichas por revisar.
---

Escribe una copia en la TV (y borra las caducadas, como «Crear copia»): **pide validación expresa antes de `--confirm`**.

    node tools/tv.js backup            # solo enseña el plan
    node tools/tv.js backup --confirm  # BACKUP + pull a backups/tv-<fecha> + verificación
    python tools/audit/audit.py pending backups/tv-<fecha>/anilist-links.json.gz

Comprueba antes en `tv.js health` que la última pasada de AniList terminó (`Pasada: … Terminada`). Crea una carpeta nueva, nunca sobrescribe. No prueba a restaurar la copia: dilo al informar. Registra el resultado en `log.md`. Documentación: `tools/README.md`.
