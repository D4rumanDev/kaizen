package app.kaizen.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo una respuesta es el reto de Cloudflare (y cuándo no: un 403 o un 503 ajenos no deben abrir un WebView). */
class CloudflareInterceptorTest {

    @Test
    fun challengeHeaderOn403Or503IsAChallenge() {
        assertTrue(CloudflareInterceptor.isCloudflareChallenge(403, "challenge"))
        assertTrue(CloudflareInterceptor.isCloudflareChallenge(503, "Challenge"))
    }

    @Test
    fun otherResponsesAreNotAChallenge() {
        assertFalse(CloudflareInterceptor.isCloudflareChallenge(200, "challenge"))
        assertFalse(CloudflareInterceptor.isCloudflareChallenge(404, "challenge"))
        assertFalse(CloudflareInterceptor.isCloudflareChallenge(403, null))
        assertFalse(CloudflareInterceptor.isCloudflareChallenge(503, null))
        assertFalse(CloudflareInterceptor.isCloudflareChallenge(403, "other"))
    }
}
