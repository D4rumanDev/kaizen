package app.kaizen.videoservers

import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ===== JkPlayerServerTest.kt =====
/** Fragmentos reales de las páginas `jkplayer/um` (Desu) y `jkplayer/umv` (Magi), 2026-10-11. */
class JkPlayerServerTest {

    private val m3u8 = "https://nika.playmudos.com/K05MYUxWYWVm.m3u8?st=D1nsn_xMWmqlK1E3ke_TtQ&e=1791754584"

    @Test
    fun namesFollowJkanimeLabels() {
        assertEquals("Desu", JkPlayerServer.nameOf("https://jkanime.net/jkplayer/um?e=abc"))
        assertEquals("Magi", JkPlayerServer.nameOf("https://jkanime.net/jkplayer/umv?e=abc"))
        assertEquals("JKPlayer", JkPlayerServer.nameOf("https://jkanime.net/jkplayer/otro?e=abc"))
    }

    @Test
    fun desuPlainUrl() {
        val html = "var dp = new DPlayer({ video: {\n url: '$m3u8',\n type: 'hls' } });"
        assertEquals(m3u8, JkPlayerServer.streamUrl(html))
    }

    @Test
    fun magiSourceTag() {
        val html = "<video><source src='$m3u8' type='application/x-mpegURL'></video>"
        assertEquals(m3u8, JkPlayerServer.streamUrl(html))
    }

    @Test
    fun encodedWithAtob() {
        val encoded = java.util.Base64.getEncoder().encodeToString(m3u8.toByteArray())
        assertEquals(m3u8, JkPlayerServer.streamUrl("video: { url: atob('$encoded') }"))
    }

    @Test
    fun pageWithoutStream() {
        assertNull(JkPlayerServer.streamUrl("<script src=\"https://cdn.jkdesa.com/assets3/js/hls.min.js\"></script>"))
    }
}

// ===== OkruUnescapeTest.kt =====
/** `data-options` de ok.ru llega con entidades HTML: el desescapado debe dejar un JSON válido (antes lo hacía commons-text). */
class OkruUnescapeTest {
    @Test
    fun desescapaComillasAmpersandYNumericas() {
        val raw = "{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;a&amp;b &#39;x&#39; &lt;p&gt; &#x41;&quot;}}"
        val json = JSONObject(Parser.unescapeEntities(raw, false))
        assertEquals("a&b 'x' <p> A", json.getJSONObject("flashvars").getString("metadata"))
    }
}

// ===== ServerResolverTest.kt =====
class ServerResolverTest {
    private fun run(items: List<String>, works: Set<String>, timeoutMs: Long = 1_000, canCheck: (String) -> Boolean = { true }): Triple<Boolean, List<String>, List<String>> {
        val list = items.toMutableList()
        val dropped = mutableListOf<String>()
        val found = runBlocking {
            ServerResolver.dropBrokenLeading(list, timeoutMs, canCheck, { dropped += it }) { it in works }
        }
        return Triple(found, list, dropped)
    }

    @Test
    fun firstWorkingStaysAndNothingIsDropped() {
        val (found, list, dropped) = run(listOf("a", "b"), setOf("a", "b"))
        assertTrue(found)
        assertEquals(listOf("a", "b"), list)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun brokenHeadsAreDroppedInOrder() {
        val (found, list, dropped) = run(listOf("a", "b", "c", "d"), setOf("c", "d"))
        assertTrue(found)
        assertEquals(listOf("c", "d"), list)
        assertEquals(listOf("a", "b"), dropped)
    }

    @Test
    fun allBrokenEmptiesTheList() {
        val (found, list, dropped) = run(listOf("a", "b"), emptySet())
        assertFalse(found)
        assertTrue(list.isEmpty())
        assertEquals(listOf("a", "b"), dropped)
    }

    @Test
    fun uncheckableHeadCountsAsGood() {
        val (found, list, _) = run(listOf("web", "a"), emptySet(), canCheck = { it != "web" })
        assertTrue(found)
        assertEquals(listOf("web", "a"), list)
    }

    @Test
    fun timeoutDropsTheHead() {
        val list = mutableListOf("slow", "fast")
        val found = runBlocking {
            ServerResolver.dropBrokenLeading(list, 50) { if (it == "slow") { delay(2_000); true } else true }
        }
        assertTrue(found)
        assertEquals(listOf("fast"), list)
    }

    @Test
    fun emptyListIsNotFound() {
        assertFalse(run(emptyList(), emptySet()).first)
    }
}

// ===== VoeServerTest.kt =====
/** Ofuscación de VOE comprobada con una página real (2026-10-11); aquí se reproduce al revés. */
class VoeServerTest {

    private val m3u8 = "https://ugc-cdn-caching-x.cloudwindow-route.com/engine/hls2-c/01/15465/abc_,n,.urlset/master.m3u8?t=x&s=1&e=14400"

    private fun rot13(text: String) = text.map { c ->
        when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

    /** Inverso de [VoeServer.decode], con separadores basura como los de la web. */
    private fun encode(json: String): String {
        val inner = Base64.getEncoder().encodeToString(json.toByteArray())
        val shifted = inner.reversed().map { it + 3 }.joinToString("")
        val outer = Base64.getEncoder().encodeToString(shifted.toByteArray(Charsets.ISO_8859_1)).trimEnd('=')
        val junk = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        val withJunk = outer.chunked(4).mapIndexed { i, part -> part + junk[i % junk.size] }.joinToString("")
        return rot13(withJunk)
    }

    private fun page(payload: String) =
        "<html><script type=\"application/json\">[\"$payload\"]</script><script src=\"/js/loader.js\"></script></html>"

    @Test
    fun decodesTheHlsSource() {
        val payload = encode("""{"source":"$m3u8","file_code":"abc","title":"ep.mp4"}""")
        assertEquals(m3u8, VoeServer.streamUrl(page(payload)))
    }

    @Test
    fun redirectPageHasNoSource() {
        assertNull(VoeServer.streamUrl("<script>window.location.href = 'https://megankiddifference.com/e/abc';</script>"))
    }

    @Test
    fun brokenPayloadIsNull() {
        assertNull(VoeServer.streamUrl(page("no-es-base64")))
    }
}

// ===== FilemoonHostsTest.kt =====
class FilemoonHostsTest {
    @Test
    fun reconoceLaWebNuevaDeFilemoon() {
        val links = listOf("https://filemoon.sx/e/abc", "https://bysekoze.com/d/7h6gkqjpku9m/", "https://byselapuix.com/e/abc")
        assertTrue(links.all { link -> FilemoonServer.HOSTS.any { link.contains(it) } })
        assertFalse(FilemoonServer.HOSTS.any { "https://uqload.is/abc".contains(it) })
    }
}
