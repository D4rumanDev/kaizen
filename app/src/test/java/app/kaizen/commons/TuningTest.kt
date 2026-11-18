package app.kaizen.commons

import app.kaizen.source.FichaFill
import app.kaizen.tv.exoplayer.SeekAcceleration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/** Los ajustes de medida: los módulos los reciben por parámetro o variable, con el valor de fábrica por defecto (se prueban sin Android). */
class TuningTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `cada opcion pasa a la siguiente y da la vuelta`() {
        val options = listOf(10, 20, 50)
        assertEquals(20, Tuning.next(options, 10))
        assertEquals(50, Tuning.next(options, 20))
        assertEquals(10, Tuning.next(options, 50))
        // Un valor guardado que ya no es opción empieza por la primera.
        assertEquals(10, Tuning.next(options, 7))
    }

    @Test
    fun `la paciencia de fabrica no cambia los tiempos`() {
        assertEquals(10.seconds, Tuning.patient(10.seconds))
    }

    @Test
    fun `los valores de fabrica estan entre las opciones de cada ajuste`() {
        assertTrue(90 in KaizenSettings.SEEK_CAP_OPTIONS)
        assertTrue(30 in KaizenSettings.RESUME_AFTER_OPTIONS)
        assertTrue(1f in KaizenSettings.PATIENCE_OPTIONS)
        assertTrue(30 in KaizenSettings.COVER_DAYS_OPTIONS)
        assertTrue(20 in KaizenSettings.IMAGE_MEMORY_OPTIONS)
        assertTrue(7 in KaizenSettings.BACKUP_DAYS_OPTIONS)
        assertTrue(30 in KaizenSettings.BACKUP_FILES_OPTIONS)
        assertTrue(512 in KaizenSettings.LOG_KB_OPTIONS)
        assertTrue(90 in KaizenSettings.STATS_DAYS_OPTIONS)
        assertTrue(30 in KaizenSettings.CHART_DAYS_OPTIONS)
        assertTrue(FichaFill.BATCH in KaizenSettings.FILL_BATCH_OPTIONS)
        assertTrue(FichaFill.DAILY_CAP in KaizenSettings.FILL_DAILY_OPTIONS)
    }

    @Test
    fun `el salto maximo al mantener la tecla es el del ajuste`() {
        assertEquals(60, SeekAcceleration.stepSeconds(30, 1000, capSeconds = 60))
        assertEquals(180, SeekAcceleration.stepSeconds(30, 1000, capSeconds = 180))
        // Sin ajuste, el de fábrica.
        assertEquals(90, SeekAcceleration.stepSeconds(30, 1000))
    }

    @Test
    fun `la tanda y el tope diario del relleno son los del ajuste`() {
        val now = 10L * 24 * 60 * 60 * 1000
        val day = FichaFill.dayOf(now)
        assertEquals(50, FichaFill.allowance(now, 0L, day, 0, batch = 50, dailyCap = 600))
        // Con 280 hechas hoy y tope 300 quedan 20; con tope 600, la tanda entera.
        assertEquals(20, FichaFill.allowance(now, 0L, day, 280, batch = 50, dailyCap = 300))
        assertEquals(50, FichaFill.allowance(now, 0L, day, 280, batch = 50, dailyCap = 600))
        assertEquals(0, FichaFill.allowance(now, 0L, day, 100, batch = 10, dailyCap = 100))
        assertEquals(FichaFill.BATCH, FichaFill.allowance(now, 0L, day, 0))
        // Un valor guardado absurdo no revienta: no se abre ninguna.
        assertEquals(0, FichaFill.allowance(now, 0L, day, 0, batch = -5, dailyCap = 300))
    }

    @Test
    fun `las copias se conservan segun los dias y el maximo del ajuste`() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        fun backup(name: String, ageDays: Int, bytes: Int) = folder.newFile(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(now - ageDays * day)
        }
        val newest = backup("a.json", 0, 100)
        val fiveDays = backup("b.json", 5, 10)
        val twentyDays = backup("c.json", 20, 10)
        val files = listOf(newest, fiveDays, twentyDays)
        // 7 días (fábrica): caduca la de 20.
        assertEquals(listOf(twentyDays), KaizenBackup.expired(files, now, keepDays = 7))
        // 3 días: caducan la de 5 y la de 20.
        assertEquals(setOf(fiveDays, twentyDays), KaizenBackup.expired(files, now, keepDays = 3).toSet())
        // 30 días: no caduca ninguna.
        assertTrue(KaizenBackup.expired(files, now, keepDays = 30).isEmpty())
        // Con un máximo de 2 ficheros sobra la más antigua aunque no haya caducado por días.
        assertEquals(listOf(twentyDays), KaizenBackup.expired(files, now, keepDays = 30, maxFiles = 2))
    }

    @Test
    fun `reanudar solo pasado el tiempo del ajuste`() {
        val original = KaizenProgress.minResumeMs
        try {
            val duration = 24 * 60_000L
            KaizenProgress.minResumeMs = 30_000L
            assertFalse(KaizenProgress.Progress(20_000, duration).isResumable)
            assertTrue(KaizenProgress.Progress(40_000, duration).isResumable)
            KaizenProgress.minResumeMs = 120_000L
            assertFalse(KaizenProgress.Progress(40_000, duration).isResumable)
            assertTrue(KaizenProgress.Progress(130_000, duration).isResumable)
            // «Siempre»: cualquier punto sin terminar se ofrece.
            KaizenProgress.minResumeMs = 0L
            assertTrue(KaizenProgress.Progress(2_000, duration).isResumable)
        } finally {
            KaizenProgress.minResumeMs = original
        }
    }
}
