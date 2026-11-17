---
name: kaizen-cierre-docs
description: Cierra un lote de documentación de Kaizen - comprueba finales de línea y datos sensibles, replica en Basic Memory con sync_docs.js y hace commit LOCAL de las rutas indicadas, sin push. Usar cuando el usuario pide "sincroniza y commit", "cierra la documentación" o "lanza sync_docs y commit" y solo cambian ficheros de documentación (pendientes.md, log.md, docs/, .vscode).
---

Solo con petición del usuario (hace commit). Desde la raíz del repo:

    node tools/ship.js docs -m "mensaje" RUTA [RUTA ...] [--dry-run] [--no-sync]

Siempre rutas explícitas: nunca incluye `CLAUDE.md`, `THIRD_PARTY.md` ni `docs/wiki/sdd/constitution.md` (los rechaza). Nunca hace push: publicar es `node tools/ship.js check` + auditoría del SHA + `publish`, con validación expresa. Si hay código en el lote, no usar esta herramienta. Antes de pedir el commit actualiza `pendientes.md` y `log.md`. Documentación: `tools/README.md`.
