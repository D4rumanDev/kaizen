package app.kaizen.source

import app.kaizen.commons.DebugLog
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que dicen las webs de una serie dudosa, juntado de las fichas que respondieron. Lo usan la pantalla de revisión ([app.kaizen.tv.settings.PendingTab]) y
 * [AnilistAutoRunner] (ADB): los dos juzgan con el mismo código, no con dos copias.
 */
internal class LocalFacts(
    /** Ya respondieron (o fallaron) todas las fichas consultadas. */
    val done: Boolean,
    val episodes: Int?, val airing: Boolean?, val genres: Collection<String>, val studio: String?, val format: String?,
    val mal: Long?, val duration: Int?, val names: List<String>, val year: Int?
) {
    companion object {
        fun of(local: Catalog.Pick?, done: Boolean, facts: List<Catalog.SourceFacts>) = LocalFacts(
            done = done,
            episodes = facts.mapNotNull { it.episodes }.maxOrNull(),
            airing = facts.takeIf { it.isNotEmpty() }?.any { it.airing },
            genres = GenreNames.anilistSet(facts.flatMap { it.genres }),
            studio = facts.firstNotNullOfOrNull { it.studio },
            format = facts.firstNotNullOfOrNull { it.formato },
            mal = facts.firstNotNullOfOrNull { it.idMal },
            // Las webs que no dan la duración la dejan vacía: sin dato no se compara.
            duration = facts.firstNotNullOfOrNull { it.duration },
            names = facts.flatMap { it.names }.distinct(),
            // El año del catálogo y, si no lo trae, el de las fichas (el más antiguo).
            year = local?.year ?: facts.mapNotNull { it.year }.minOrNull()
        )
    }
}

/** El juicio sobre una serie dudosa y su candidato de AniList. */
internal class Judgement(
    val localName: String, val titleScore: Double?, val compared: AnilistCompare.Compared,
    /** El candidato ya no se parece al título (se guardó con un matcher anterior): se descarta sin más. */
    val discard: Boolean,
    /** Qué hacer sin preguntar; null mientras falten datos (la serie del catálogo o las fichas de las webs). */
    val verdict: AnilistCompare.Auto?
)

/** Qué se hace con una serie dudosa tras juzgarla. */
internal enum class AutoOutcome {
    ACCEPT, DISCARD, ASK, NO_DATA;

    companion object {
        /** Del juicio a la decisión: un candidato que ya no se parece al título o un rechazo claro se descarta; una aceptación clara se acepta; lo demás lo decide una persona. */
        fun of(discard: Boolean, verdict: AnilistCompare.Auto?): AutoOutcome = when {
            discard || verdict == AnilistCompare.Auto.REJECT -> DISCARD
            verdict == AnilistCompare.Auto.ACCEPT -> ACCEPT
            else -> ASK
        }

        /** Ejecuta [outcome]: solo con [apply] escribe (acepta o descarta); sin él, la prueba solo cuenta. Devuelve [outcome]. */
        suspend fun resolve(outcome: AutoOutcome, apply: Boolean, accept: suspend () -> Unit, discard: suspend () -> Unit): AutoOutcome {
            if (apply) when (outcome) {
                ACCEPT -> accept()
                DISCARD -> discard()
                else -> Unit
            }
            return outcome
        }
    }
}

internal object AnilistAutoReview {
    /** Único sitio donde se compara una serie dudosa con su candidato; la pantalla pinta el resultado y ADB lo aplica. */
    fun judge(first: Pair<String, String>, local: Catalog.Pick?, facts: LocalFacts, candidate: FichaData?): Judgement {
        val localName = local?.name ?: titleFromSlug(first.second)
        val titleScore = AnilistCompare.titleScore(localName, candidate)
        val discard = local != null && candidate != null && titleScore != null && titleScore < AnilistMatcher.DUDOSO_FROM
        val compared = AnilistCompare.compare(candidate, titleScore, facts.year, facts.episodes, facts.genres, facts.airing, facts.studio, facts.format, facts.mal, facts.names, facts.duration)
        return Judgement(localName, titleScore, compared, discard, if (local != null && facts.done) compared.auto else null)
    }
}

/**
 * Recorre las series dudosas con AniList sin pantalla (acción ADB `ANILIST_AUTO`) y resuelve las claras con el mismo juicio que la revisión:
 * acepta las que lo piden ([AnilistCompare.Auto.ACCEPT]), descarta las que no ([AnilistCompare.Auto.REJECT] o un candidato que ya no se parece al título) y
 * deja para una persona las demás. **Por defecto solo cuenta** (no escribe enlaces ni decisiones; solo la caché de respuestas de las webs, como la pantalla); con `apply` hace antes una
 * copia, guarda los enlaces y, al acabar, reagrupa el catálogo.
 * Una serie a la vez, con el ritmo de AniList y de las webs: tarda lo que tarde la pantalla en recorrerlas, pero sin nadie delante.
 */
internal object AnilistAutoRunner {
    data class Progress(
        val running: Boolean = false, val apply: Boolean = false, val total: Int = 0, val done: Int = 0,
        val accepted: Int = 0, val discarded: Int = 0, val ask: Int = 0, val noData: Int = 0, val startedAt: Long = 0L, val finishedAt: Long = 0L
    ) {
        /** Cuenta una serie más con su resultado (null: falló al evaluarla, cuenta como sin datos). */
        fun counted(outcome: AutoOutcome?) = copy(
            done = done + 1,
            accepted = accepted + if (outcome == AutoOutcome.ACCEPT) 1 else 0,
            discarded = discarded + if (outcome == AutoOutcome.DISCARD) 1 else 0,
            ask = ask + if (outcome == AutoOutcome.ASK) 1 else 0,
            noData = noData + if (outcome == null || outcome == AutoOutcome.NO_DATA) 1 else 0
        )

        val line: String
            get() = "estado=${if (running) "en curso" else if (finishedAt > 0) "terminado" else "parado"} modo=${if (apply) "aplicando" else "prueba (no escribe)"} " +
                "total=$total hechas=$done aceptarian=$accepted descartarian=$discarded preguntarian=$ask sin_datos=$noData"
    }

    @Volatile var progress = Progress()
        private set

    @Synchronized fun start(apply: Boolean, limit: Int): Boolean {
        if (progress.running) return false
        progress = Progress(running = true, apply = apply, startedAt = System.currentTimeMillis())
        app.kaizen.commons.AppScope.launch(Dispatchers.IO + app.kaizen.commons.RequestCounter.Background) { run(apply, limit) }
        return true
    }

    private suspend fun run(apply: Boolean, limit: Int) {
        try {
            // Aplicar escribe enlaces y reagrupa el catálogo: antes, una copia nueva (la misma que «Crear copia»). Primero la copia (antes incluso de las adopciones de pending): si falla, no se escribe nada.
            if (apply) {
                val (file, summary) = app.kaizen.commons.KaizenBackup.export()
                DebugLog.info("AniList", "Copia antes de aplicar: ${file.name} ($summary)")
            }
            // La prueba no escribe nada: ni siquiera enlaza las de un anime ya confirmado, que sí hace la pantalla al abrirse.
            val pending = AnilistReviewRepository.pending(adopt = apply).take(limit)
            progress = progress.copy(total = pending.size)
            DebugLog.info("AniList", "Revisión por ADB (${if (apply) "aplicando" else "prueba"}): ${pending.size} series dudosas")
            for (series in pending) {
                val outcome = runCatchingCoop { evaluate(series, apply) }.onFailure(::logError).getOrNull()
                progress = progress.counted(outcome)
            }
            DebugLog.info("AniList", "Revisión por ADB terminada: ${progress.line}")
            // Como al cerrar la pantalla: los enlaces nuevos pueden unir series de distintas fuentes.
            if (apply && progress.accepted > 0) runCatchingCoop { CatalogBuilder.regroup() }.onFailure(::logError)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logError(e)
            DebugLog.info("AniList", "Revisión por ADB fallida: ${e.javaClass.simpleName}")
        } finally {
            progress = progress.copy(running = false, finishedAt = System.currentTimeMillis())
        }
    }

    private suspend fun evaluate(series: PendingSeries, apply: Boolean): AutoOutcome {
        val first = series.fichas.first()
        val local = Catalog.pickOfFicha(first.first, first.second)
        val facts = coroutineScope { series.fichas.take(4).map { (source, slug) -> async { runCatchingCoop { Catalog.facts(source, slug) }.getOrNull() } }.awaitAll() }.filterNotNull()
        // Sin ninguna ficha de las webs no hay con qué comparar (la red cayó o las webs no respondieron): en la pantalla decide una persona, aquí no se decide solo.
        if (facts.isEmpty()) return AutoOutcome.NO_DATA
        val raw = AnilistReview.candidate(series.anilistId) ?: return AutoOutcome.NO_DATA
        val candidate = FichaData.ofAnilistJson(raw.json)
        val judgement = AnilistAutoReview.judge(first, local, LocalFacts.of(local, true, facts), candidate)
        val at = System.currentTimeMillis()
        return AutoOutcome.resolve(
            AutoOutcome.of(judgement.discard, judgement.verdict), apply,
            accept = { withContext(Dispatchers.IO) { AnilistReviewRepository.accept(series, judgement.titleScore ?: 0.0, raw, at) } },
            discard = { withContext(Dispatchers.IO) { AnilistReviewRepository.discard(series, at) } }
        ).also { if (it != AutoOutcome.ASK && apply) DebugLog.info("AniList", "Dudosa resuelta sola por ADB (${it.name}): ${first.second} → #${series.anilistId}") }
    }
}
