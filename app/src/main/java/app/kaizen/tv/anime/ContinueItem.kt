package app.kaizen.tv.anime

import app.kaizen.commons.KaizenProgress
import app.kaizen.pojos.av1.Record

/** Kaizen: elemento de «Seguir viendo»: capítulo empezado y su progreso. */
data class ContinueItem(val record: Record, val progress: KaizenProgress.Progress)
