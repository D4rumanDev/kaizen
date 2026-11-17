package app.kaizen.tv.exoplayer

import app.kaizen.tv.ui.theme.*

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import app.kaizen.tv.TVBaseActivity
import app.kaizen.tv.TVServersFactory

/** Reproductor: la pantalla es [PlayerScreen] (Compose) y lo que hace, [PlaybackController]. */
class TVPlayer : TVBaseActivity(), TVServersFactory.ServersInterface {
    // Kaizen: capítulo anterior/siguiente pedido desde el propio reproductor.
    private lateinit var playback: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playback = PlaybackController(this)
        setContent { KaizenTheme { PlayerScreen(playback) } }
    }

    override fun onStart() {
        super.onStart()
        app.kaizen.source.AnilistSyncRunner.playerStarted()
        playback.onStart()
    }

    override fun onPause() {
        playback.onPause()
        super.onPause()
    }

    override fun onStop() {
        playback.onStop()
        app.kaizen.source.AnilistSyncRunner.playerStopped()
        super.onStop()
    }

    /** El capítulo nuevo se abre en otro reproductor: este se cierra. */
    override fun onFinish(started: Boolean, success: Boolean) {
        if (success) runOnUiThread { finish() }
    }
}
