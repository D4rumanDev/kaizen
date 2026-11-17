package app.kaizen.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import app.kaizen.databinding.ActivityBrowserBinding

class WebPlayerActivity : AppCompatActivity() {
    val binding by lazy { ActivityBrowserBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            app.kaizen.tv.ui.KaizenToast.toastLong("Webview no está disponible en tu dispositivo")
            finish()
            return
        }
        setContentView(binding.root)
        binding.webview.apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return true
                }
               /* override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    //view?.loadUrl(url ?: return true)
                    return true
                }*/

                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.loading.isVisible = false
                }
            }
            //loadUrl(intent.dataString?:"about:blank")
            // Solo páginas web: un enlace javascript: o file: no se carga.
            intent.dataString?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }?.let {
                //loadData(framed(it), "text/html; charset=utf-8", "UTF-8")
                loadUrl(it)
            } ?: finish()
        }
        // Pantalla completa (sin barras del sistema; reaparecen con un gesto), con la API de insets en lugar de systemUiVisibility.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

fun openWebPlayer(context: Context, link: String, title: String){
    context.startActivity(Intent(context, WebPlayerActivity::class.java).apply {
        data = link.toUri()
        //putExtra("is_web", true)
        putExtra("title", title)
    })
}