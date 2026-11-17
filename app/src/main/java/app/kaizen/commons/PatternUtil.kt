package app.kaizen.commons

import java.util.regex.Pattern

object PatternUtil {

    fun getRapidLink(link: String): String {
        val pattern = Pattern.compile("value=([\\w#.]+)")
        val matcher = pattern.matcher(link)
        // Sin coincidencia, `group` lanzaría «No successful match so far» sin decir de qué se trataba.
        if (!matcher.find()) error("getRapidLink: sin coincidencia en la página (¿cambió o devolvió una verificación?)")
        return "https://www.rapidvideo.com/e/" + matcher.group(1)
    }

    fun getRapidVideoLink(link: String): String {
        val pattern = Pattern.compile("\"(http.*\\.mp4)\"")
        val matcher = pattern.matcher(link)
        // Sin coincidencia, `group` lanzaría «No successful match so far» sin decir de qué se trataba.
        if (!matcher.find()) error("getRapidVideoLink: sin coincidencia en la página (¿cambió o devolvió una verificación?)")
        return matcher.group(1) ?: error("sin coincidencia")
    }

    fun getYUvideoLink(link: String): String {
        val pattern = Pattern.compile("file: ?'(.*vidcache.*mp4)'")
        val matcher = pattern.matcher(link)
        // Sin coincidencia, `group` lanzaría «No successful match so far» sin decir de qué se trataba.
        if (!matcher.find()) error("getYUvideoLink: sin coincidencia en la página (¿cambió o devolvió una verificación?)")
        return matcher.group(1) ?: error("sin coincidencia")
    }

    fun extractLink(html: String): String {
        val matcher = Pattern.compile("https?://[a-zA-Z0-9.=?/!&#_\\-]+|/[a-zA-Z0-9.=?/!&#_\\-]+").matcher(html)
        // Sin coincidencia, `group` lanzaría «No successful match so far» sin decir de qué se trataba.
        if (!matcher.find()) error("extractLink: sin coincidencia en la página (¿cambió o devolvió una verificación?)")
        return matcher.group(0) ?: error("sin coincidencia")
    }

    fun extractMediaLink(html: String): String {
        val matcher = Pattern.compile("www\\.mediafire[a-zA-Z0-a.=?/&%]+").matcher(html)
        // Sin coincidencia, `group` lanzaría «No successful match so far» sin decir de qué se trataba.
        if (!matcher.find()) error("extractMediaLink: sin coincidencia en la página (¿cambió o devolvió una verificación?)")
        return "https://" + matcher.group().replace("%2F", "/")
    }

}
