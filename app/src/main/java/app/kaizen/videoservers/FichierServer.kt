package app.kaizen.videoservers

import android.content.Context

class FichierServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("1fichier")

    override val name: String
        get() = "1Fichier (WEB)"

    override val canStream: Boolean
        get() = false

    override suspend fun videoServer(): VideoServer =
        VideoServer(name, Option(name, null, baseLink, needTabs = true), skipVerification = true)
}
