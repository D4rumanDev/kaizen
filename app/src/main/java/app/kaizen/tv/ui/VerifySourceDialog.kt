package app.kaizen.tv.ui

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.appString
import app.kaizen.retrofit.Repository
import app.kaizen.source.SourceChallenge

/**
 * La web [host] pide una verificación de seguridad: se abre aquí, a la vista, para que la haga el usuario. Con «Listo» se guardan las
 * cookies de esa sesión y el User-Agent del navegador (ver [SourceChallenge]) y se recargan los recientes; con «Cancelar» no se guarda nada.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun VerifySourceDialog(host: String, onDone: () -> Unit) {
    // Se abre la página que dispara el desafío (animegratis.net: /horario); las cookies valen para todo el sitio, así que se leen de la raíz.
    val url = "https://$host/"
    val startUrl = SourceChallenge.verifyUrl(host)
    val webView = remember { arrayOfNulls<WebView>(1) }
    // «Atrás» del mando: primero vuelve a la página anterior de la web (el captcha lleva a otras páginas); sin historial, cierra sin guardar.
    fun back() {
        val view = webView[0]
        if (view != null && view.canGoBack()) view.goBack() else onDone()
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appString(R.string.ui_verificar_titulo, host), color = Color.White, fontSize = Text17, fontWeight = FontWeight.Bold)
            Text(appString(R.string.ui_verificar_nota), color = White70, fontSize = Text14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KaizenButton(appString(R.string.ui_verificar_atras), onClick = ::back)
                KaizenButton(appString(R.string.ui_verificar_inicio), onClick = { webView[0]?.loadUrl(url) })
                KaizenButton(appString(R.string.ui_verificar_listo), onClick = {
                    val view = webView[0]
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (view != null && !cookie.isNullOrBlank() && SourceChallenge.save(host, cookie, view.settings.userAgentString)) {
                        Repository.reloadRecents(force = true)
                        onDone()
                    } else {
                        KaizenToast.toast(appString(R.string.ui_verificar_fallo))
                    }
                })
                KaizenButton(appString(R.string.ui_cancelar), onClick = onDone)
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRelease = { it.destroy() },
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = WebViewClient()
                        loadUrl(startUrl)
                        webView[0] = this
                    }
                }
            )
        }
    }
}
