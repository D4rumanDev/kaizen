package app.kaizen.commons

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.kaizen.App

@SuppressLint("StaticFieldLeak")
object Network {

    /**
     * ¿Hay **alguna** red con salida a Internet? A diferencia de [isConnected] (solo mira la red activa), no da «no» en el instante de un cambio de red, cuando la activa es
     * nula. No exige que Android la haya «validado»: esa comprobación va a servidores de Google y un DNS que los filtre (AdGuard) la deja sin validar con Internet funcionando.
     * Lanza si no se puede consultar: quien la usa decide qué suponer entonces.
     */
    @Suppress("DEPRECATION")
    fun anyInternet(): Boolean {
        val cm = App.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
    }

    val isConnected: Boolean
        get() {
            return try {
                val cm = App.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)
                capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (e: Exception) {
                false
            }

        }

}
