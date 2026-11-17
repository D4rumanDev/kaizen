package app.kaizen.commons

import androidx.test.runner.AndroidJUnit4
import app.kaizen.pojos.av1.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El progreso se guarda por ficha y número, y lo guardado por eid en versiones anteriores se sigue leyendo y pasa a la clave nueva sin
 * perderse. Usa el fichero de progreso real de la app con eids y slugs de prueba: ejecutar solo en un emulador.
 */
@RunWith(AndroidJUnit4::class)
class KaizenProgressStoreTest {
    private val position = 400_000L
    private val duration = 1_400_000L

    // El fichero de progreso es el real y persiste entre ejecuciones: cada prueba empieza sin lo que usa.
    @Before
    fun limpiar() {
        (920_001L..920_009L).forEach { id -> (0..7).forEach { KaizenProgress.remove(KaizenProgress.Key(id, it.toDouble()), 0) } }
        (910_001..910_008).forEach { KaizenProgress.remove(KaizenProgress.Key(1L, 0.0), it) }
    }

    private fun legacy(eid: Int) = KaizenProgress.importMissing(mapOf(eid.toString() to "300000/$duration"))

    @Test
    fun loGuardadoPorEidPasaALaClaveDeLaSerieConElHistorial() {
        val record = Record(eid = 910_001, name = "Prueba", number = 2.0, aid = 1, slug = "prueba-migracion", date = 0L, source = "jkanime", seriesId = 920_001L)
        assertEquals(1, legacy(record.eid))

        KaizenProgress.migrateEidKeys(listOf(record))

        assertEquals(300_000L, KaizenProgress.get(record.progressKey(), record.eid)?.positionMs)
        assertFalse(KaizenProgress.exportAll().containsKey(record.eid.toString()))
        assertTrue(KaizenProgress.exportAll().containsKey("s:920001#2"))
        assertNotNull(KaizenProgress.inProgress()[record.progressKey()])
    }

    @Test
    fun loGuardadoPorEidSinRegistroSeSigueLeyendoYNoSePierde() {
        val key = KaizenProgress.Key(920002L, 4.0)
        assertEquals(1, legacy(910_002))

        KaizenProgress.migrateEidKeys(emptyList())

        assertEquals(300_000L, KaizenProgress.get(key, 910_002)?.positionMs)
        assertTrue(KaizenProgress.exportAll().containsKey("910002"))
        assertNull(KaizenProgress.get(key, 0))
    }

    @Test
    fun guardarConLaClaveNuevaQuitaLaAntiguaDelMismoCapitulo() {
        val key = KaizenProgress.Key(920003L, 7.0)
        assertEquals(1, legacy(910_003))

        KaizenProgress.save(key, 910_003, position, duration)

        assertEquals(position, KaizenProgress.get(key, 910_003)?.positionMs)
        assertFalse(KaizenProgress.exportAll().containsKey("910003"))
        assertEquals(position, KaizenProgress.inProgress()[key]?.positionMs)
    }

    @Test
    fun sinFichaSeGuardaConElEidParaNoPerderlo() {
        KaizenProgress.save(null, 910_004, position, duration)
        KaizenProgress.save(KaizenProgress.Key(0L, 1.0), 910_005, position, duration)

        assertTrue(KaizenProgress.exportAll().containsKey("910004"))
        assertTrue(KaizenProgress.exportAll().containsKey("910005"))
    }

    @Test
    fun quitarBorraLaClaveNuevaYLaAntigua() {
        val key = KaizenProgress.Key(920004L, 1.0)
        assertEquals(1, legacy(910_006))
        KaizenProgress.save(KaizenProgress.Key(920005L, 1.0), 910_007, position, duration)

        KaizenProgress.remove(key, 910_006)

        assertNull(KaizenProgress.get(key, 910_006))
        assertFalse(KaizenProgress.exportAll().containsKey("910006"))
        assertNotNull(KaizenProgress.get(KaizenProgress.Key(920005L, 1.0), 910_007))
    }

    @Test
    fun laCopiaDeSeguridadLlevaYRestauraLasDosClaves() {
        val saved = mapOf("s:920006#3" to "500000/$duration", "910008" to "600000/$duration", "no-es-progreso" to "x/y")

        assertEquals(2, KaizenProgress.importMissing(saved))
        assertEquals(0, KaizenProgress.importMissing(saved))

        val exported = KaizenProgress.exportAll()
        assertEquals("500000/$duration", exported["s:920006#3"])
        assertEquals("600000/$duration", exported["910008"])
        assertFalse(exported.containsKey("no-es-progreso"))
    }

    @Test
    fun elRecuentoYElProgresoDeLaFichaIncluyenLoGuardadoPorEidSinRegistro() {
        val before = KaizenProgress.inProgressCount()
        val key = KaizenProgress.Key(920002L, 4.0)
        assertEquals(1, legacy(910_002))
        assertEquals(1, KaizenProgress.importMissing(mapOf("s:920007#1" to "300000/$duration")))

        assertEquals(before + 2, KaizenProgress.inProgressCount())
        assertEquals(300_000L, KaizenProgress.startedOf(key, 910_002)?.positionMs)
        assertNull(KaizenProgress.startedOf(key, 0))
    }

    @Test
    fun loGuardadoPorFichaPasaALaClaveDeSuSerieYSoloSiElLibroLaConoce() {
        KaizenProgress.importMissing(mapOf(
            "p:jkanime:prueba-ficha#3" to "500000/$duration", "p:jkanime:prueba-ficha-sin-serie#1" to "500000/$duration"
        ))

        KaizenProgress.migrateFichaKeys { _, slug -> if (slug == "prueba-ficha") 920_008L else 0L }

        val exported = KaizenProgress.exportAll()
        assertEquals("500000/$duration", exported["s:920008#3"])
        assertFalse(exported.containsKey("p:jkanime:prueba-ficha#3"))
        assertTrue(exported.containsKey("p:jkanime:prueba-ficha-sin-serie#1"))
        KaizenProgress.migrateFichaKeys { _, _ -> 920_009L }
        KaizenProgress.remove(KaizenProgress.Key(920_009L, 1.0), 0)
    }

    @Test
    fun laCopiaPortableLlevaLaFichaDeLaSerieEnVezDeSuIdLocal() {
        KaizenProgress.save(KaizenProgress.Key(920_009L, 5.0), 0, position, duration)

        val exported = KaizenProgress.exportPortable { id -> if (id == 920_009L) "jkanime" to "prueba-portable" else null }

        assertEquals("$position/$duration", exported["p:jkanime:prueba-portable#5"])
        assertFalse(exported.containsKey("s:920009#5"))
    }

    @Test
    fun elProgresoDeUnaSerieUnidaPasaALaVigenteYConservaElDeMasPosicion() {
        KaizenProgress.save(KaizenProgress.Key(920_010L, 2.0), 0, 500_000L, duration)
        KaizenProgress.save(KaizenProgress.Key(920_010L, 3.0), 0, 400_000L, duration)
        KaizenProgress.save(KaizenProgress.Key(920_011L, 3.0), 0, 600_000L, duration)

        KaizenProgress.rekey { if (it == 920_010L) 920_011L else it }

        val exported = KaizenProgress.exportAll()
        assertEquals("500000/$duration", exported["s:920011#2"])
        assertEquals("600000/$duration", exported["s:920011#3"])
        assertFalse(exported.keys.any { it.startsWith("s:920010#") })
        KaizenProgress.remove(KaizenProgress.Key(920_011L, 2.0), 0)
        KaizenProgress.remove(KaizenProgress.Key(920_011L, 3.0), 0)
    }
}
