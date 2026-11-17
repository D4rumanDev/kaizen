package app.kaizen.uagen

import kotlin.random.Random

/** User-Agents de Chrome para Android; sin dependencias externas. */
object UAGenerator {
    /** Versión mayor de Chrome que se considera la última. */
    private const val LATEST_CHROME = 140
    private const val OLDEST_CHROME = 128

    private val devices = listOf(
        "Linux; Android 14; Pixel 8",
        "Linux; Android 14; SM-S918B",
        "Linux; Android 13; Pixel 7",
        "Linux; Android 13; SM-A546B",
        "Linux; Android 12; Redmi Note 11",
        "Linux; Android 11; SHIELD Android TV"
    )

    private fun build(device: String, chrome: Int) =
        "Mozilla/5.0 ($device) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Mobile Safari/537.36"

    fun getRandomUserAgent(): String =
        build(devices.random(), Random.nextInt(OLDEST_CHROME, LATEST_CHROME + 1))
}
