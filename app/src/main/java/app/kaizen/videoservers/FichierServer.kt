package app.kaizen.videoservers

import android.content.Context

class FichierServer(context: Context, baseLink: String) : Server(context, baseLink, "1Fichier (WEB)", "1fichier") {

    override val canStream: Boolean
        get() = false

    override suspend fun videoServer(): VideoServer =
        VideoServer(name, Option(name, null, baseLink, needTabs = true), skipVerification = true)
}
