package app.kaizen.domain

import app.kaizen.domain.StatusMigration.Subject
import app.kaizen.domain.StatusMigration.Verdict
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las reglas de la migración de estados, sin Android: cada serie recibe un veredicto determinista. */
class StatusMigrationTest {
    private fun subject(wholeSeen: Int = 0, hasHistory: Boolean = true, source: String = "animeav1") = Subject("Serie", source, "serie", 1, wholeSeen, hasHistory)

    private fun decide(subject: Subject, airing: Boolean = false, favorite: Boolean = false, active: Boolean = true, total: Int? = 12) =
        runBlocking { StatusMigration.decide(subject, airing, favorite, { active }) { total } }

    @Test fun `en emision se esta viendo`() = assertEquals(Verdict.Mark(SeriesStatus.WATCHING), decide(subject(), airing = true))

    @Test fun `una favorita sin nada visto queda pendiente`() = assertEquals(Verdict.Mark(SeriesStatus.PLAN), decide(subject(hasHistory = false), favorite = true))

    @Test fun `con todos los episodios vistos queda completada`() = assertEquals(Verdict.Mark(SeriesStatus.COMPLETED), decide(subject(wholeSeen = 12)))

    @Test fun `una serie del historial sin terminar se deja como esta`() = assertEquals(Verdict.Skip, decide(subject(wholeSeen = 5)))

    @Test fun `una favorita con algo visto sin terminar se esta viendo`() = assertEquals(Verdict.Mark(SeriesStatus.WATCHING), decide(subject(wholeSeen = 5), favorite = true))

    @Test fun `si la fuente no responde no se puede decidir`() = assertEquals(Verdict.Unknown, decide(subject(wholeSeen = 12), total = null))

    @Test fun `una fuente desactivada no se consulta ni deja el calculo incompleto`() {
        var asked = false
        val verdict = runBlocking { StatusMigration.decide(subject(wholeSeen = 12), airing = false, isFavorite = false, active = { false }) { asked = true; 12 } }
        assertEquals(Verdict.Skip, verdict)
        assertFalse(asked)
    }

    @Test fun `una serie sin episodios publicados no se da por completada`() {
        assertFalse(StatusMigration.finished(0, 0))
        assertTrue(StatusMigration.finished(12, 12))
    }
}

/** La pantalla de dudosas de AniList: un estado por situación. */
class ReviewScreenTest {
    private fun pending() = app.kaizen.source.PendingSeries(listOf("animeav1" to "a"), 1L, "datos", 0.5, 0)

    @Test fun `mientras carga`() = assertEquals(app.kaizen.tv.settings.ReviewScreen.Loading, app.kaizen.tv.settings.reviewScreenOf(null, 0))

    @Test fun `si la lectura falla`() = assertEquals(app.kaizen.tv.settings.ReviewScreen.Failed, app.kaizen.tv.settings.reviewScreenOf(Result.failure(IllegalStateException("x")), 0))

    @Test fun `sin dudosas`() = assertEquals(app.kaizen.tv.settings.ReviewScreen.Finished(empty = true), app.kaizen.tv.settings.reviewScreenOf(Result.success(emptyList()), 0))

    @Test fun `con una pregunta y al pasar la ultima`() {
        val list = Result.success(listOf(pending(), pending()))
        assertEquals(2, (app.kaizen.tv.settings.reviewScreenOf(list, 1) as app.kaizen.tv.settings.ReviewScreen.Asking).total)
        assertEquals(app.kaizen.tv.settings.ReviewScreen.Finished(empty = false), app.kaizen.tv.settings.reviewScreenOf(list, 2))
    }
}

/** El estado de la pantalla de dudosas como flujos: la lectura, el avance y el candado de escritura. */
class ReviewSessionTest {
    private fun pending() = app.kaizen.source.PendingSeries(listOf("animeav1" to "a"), 1L, "datos", 0.5, 0)
    private fun session(list: List<app.kaizen.source.PendingSeries>) =
        app.kaizen.tv.settings.ReviewSession(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) { Result.success(list) }

    @Test fun `la pantalla sigue a la lectura y al avance`() {
        val session = session(listOf(pending(), pending()))
        assertTrue(session.screen.value is app.kaizen.tv.settings.ReviewScreen.Asking)
        session.update { it.resolved() }
        session.update { it.resolved() }
        assertEquals(app.kaizen.tv.settings.ReviewScreen.Finished(empty = false), session.screen.value)
    }

    @Test fun `solo una pulsacion toma el candado`() {
        val session = session(listOf(pending()))
        assertTrue(session.claim())
        assertFalse(session.claim())
        session.update { it.resolved() }
        assertTrue(session.claim())
    }

    @Test fun `sin decisiones no hay nada que deshacer`() = assertEquals(null, session(listOf(pending())).takeUndo())
}

/** Una petición saltada porque la web está en pausa no es un error nuevo, ni aunque venga como causa de otro. */
class SkippedRequestTest {
    @Test fun `una web en pausa o con la cola llena se reconoce tambien como causa`() {
        val down = app.kaizen.commons.HostGate.HostDownException("x.net")
        assertTrue(app.kaizen.commons.AppErrors.isSkippedRequest(down))
        assertTrue(app.kaizen.commons.AppErrors.isSkippedRequest(app.kaizen.commons.HostGate.HostBusyException("x.net")))
        assertTrue(app.kaizen.commons.AppErrors.isSkippedRequest(java.io.IOException("x no respondió", down)))
        assertFalse(app.kaizen.commons.AppErrors.isSkippedRequest(java.io.IOException("x no respondió")))
    }

    @Test fun `la clasificacion de las estadisticas no cambia para ellas`() {
        val cause = app.kaizen.commons.AppErrors.classify(app.kaizen.commons.HostGate.HostDownException("x.net"), app.kaizen.commons.AppErrors.Area.SOURCE, online = true)
        assertEquals(app.kaizen.domain.ConnectionStats.Cause.OTHER, cause)
    }
}

/** Un 404 dice qué fuente y qué se pedía, sin parámetros de la dirección. */
class ErrorWhatTest {
    @Test fun `el detalle lleva la ruta que se pedia`() {
        app.kaizen.commons.AppErrors.clear()
        app.kaizen.commons.AppErrors.report(java.io.IOException("HTTP 404"), app.kaizen.commons.AppErrors.Area.IMAGE, "AnimeAV1 · animeav1.com", what = "/covers/123.jpg")
        val report = app.kaizen.commons.AppErrors.recent().last()
        assertEquals("AnimeAV1 · animeav1.com", report.context)
        assertTrue("/covers/123.jpg" in report.incident.detail)
        app.kaizen.commons.AppErrors.clear()
    }
}

/** La lectura única de una pantalla: cargando, dato o error, sin `null` que signifique «cargando». */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class LoadStateTest {
    @Test fun `publica el dato leido`() {
        val state = app.kaizen.tv.ui.LoadState { 42 }
        assertTrue(state.state.value is app.kaizen.tv.ui.Loadable.Loading)
        runBlocking { state.start() }
        assertEquals(42, (state.state.value as app.kaizen.tv.ui.Loadable.Ready).value)
    }

    @Test fun `una excepcion queda como fallo en vez de tirar la pantalla`() {
        val state = app.kaizen.tv.ui.LoadState<Int> { error("boom") }
        runBlocking { state.start() }
        assertTrue(state.state.value is app.kaizen.tv.ui.Loadable.Failed)
    }

    @Test fun `una cancelacion no es un fallo`() {
        val state = app.kaizen.tv.ui.LoadState<Int> { kotlinx.coroutines.awaitCancellation() }
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { state.start() }
        job.cancel()
        runBlocking { job.join() }
        assertTrue(state.state.value is app.kaizen.tv.ui.Loadable.Loading)
    }
}

/** Una serie en emisión no puede quedar «Completada» aunque tenga todo visto. */
class AfterAllSeenTest {
    private val after = SeriesStatuses::afterAllSeen

    @Test fun `finalizada con todo visto queda completada`() {
        assertEquals(SeriesStatus.COMPLETED, after(SeriesStatus.WATCHING, false))
        assertEquals(SeriesStatus.COMPLETED, after(SeriesStatus.NONE, false))
        assertEquals(null, after(SeriesStatus.COMPLETED, false))
    }

    @Test fun `en emision nunca queda completada`() {
        assertEquals(SeriesStatus.WATCHING, after(SeriesStatus.NONE, true))
        assertEquals(SeriesStatus.WATCHING, after(SeriesStatus.PLAN, true))
        assertEquals(null, after(SeriesStatus.WATCHING, true))
    }

    @Test fun `una descartada no se toca`() {
        assertEquals(null, after(SeriesStatus.DROPPED, true))
        assertEquals(null, after(SeriesStatus.DROPPED, false))
    }
}

/** Las uniones con AniList de una fuente desactivada no se enseñan. */
class OnlyActiveTest {
    private fun pending(vararg fichas: Pair<String, String>) = app.kaizen.source.PendingSeries(fichas.toList(), 7L, "datos", 0.5, 3)
    private val only = app.kaizen.source.AnilistReviewRepository::onlyActive

    @Test fun `una serie solo de fuentes desactivadas desaparece`() {
        assertEquals(0, only(listOf(pending("monoschinos" to "a")), setOf("animeav1")).size)
    }

    @Test fun `una serie mixta conserva solo las fichas activas y sus datos`() {
        val result = only(listOf(pending("animeav1" to "a", "monoschinos" to "a")), setOf("animeav1")).single()
        assertEquals(listOf("animeav1" to "a"), result.fichas)
        assertEquals(7L, result.anilistId)
        assertEquals(3, result.priority)
    }

    @Test fun `una serie toda activa queda igual`() {
        val original = pending("animeav1" to "a", "jkanime" to "a")
        assertTrue(only(listOf(original), setOf("animeav1", "jkanime")).single() === original)
    }
}
