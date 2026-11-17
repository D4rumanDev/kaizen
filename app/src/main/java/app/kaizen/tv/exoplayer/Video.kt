package app.kaizen.tv.exoplayer

import android.content.Intent
import android.net.Uri

class Video(intent: Intent) {
    internal var uri: Uri = Uri.parse(intent.dataString)
    internal var title: String? = null
    internal var chapter: String? = null
    internal var headers: Map<String, String>? = null
    internal var eid: Int = -1
    internal var animeUrl: String? = null
    internal var number: Double = -1.0
    internal var server: String? = null
    internal var aid: Int = -1
    internal var slug: String = ""
    internal var source: String = ""
    /** Nombre del servidor y fuente que ofreció el enlace, como los guardan las estadísticas de conexión (para diagnosticar de quién es un fallo). */
    internal var serverName: String = ""
    internal var linkSource: String = ""

    init {
        this.title = intent.getStringExtra("title")
        this.chapter = intent.getStringExtra("chapter")
        this.eid = intent.getIntExtra("eid", -1)
        this.animeUrl = intent.getStringExtra("anime_url")
        this.number = intent.getDoubleExtra("number", -1.0)
        this.server = intent.getStringExtra("server")
        this.aid = intent.getIntExtra("aid", -1)
        this.slug = intent.getStringExtra("slug").orEmpty()
        this.source = intent.getStringExtra("source").orEmpty()
        this.serverName = intent.getStringExtra("server_name").orEmpty()
        this.linkSource = intent.getStringExtra("link_source").orEmpty()
        this.headers = intent.getStringArrayListExtra("headers")?.chunked(2)?.associate { Pair(it[0], it[1]) }?.ifEmpty { null }
    }
}
