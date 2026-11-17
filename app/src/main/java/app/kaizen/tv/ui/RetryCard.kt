package app.kaizen.tv.ui

import androidx.annotation.DrawableRes
import app.kaizen.R
import app.kaizen.commons.appString

/** Elemento que se pinta como tarjeta de icono y texto (categorías de ajustes, reintentar). */
interface IconTextItem {
    val title: String
    @get:DrawableRes val icon: Int
}

/** Tarjeta que sustituye a una sección que no se pudo cargar; al pulsarla se reintenta. */
class RetryCard(override val title: String, val retry: () -> Unit) : IconTextItem {
    override val icon: Int get() = R.drawable.ic_sync
}

/** Tarjeta de una web que pide verificación de seguridad: al pulsarla se abre la web para que la haga el usuario. */
data class VerifyCard(val host: String) : IconTextItem {
    override val title: String get() = appString(R.string.ui_verificar_tarjeta, host)
    override val icon: Int get() = R.drawable.ic_sync
}

/**
 * Marcador de «esta sección aún está cargando» en las listas de las secciones. No se pinta como tarjeta: las
 * páginas lo quitan y, si no hay nada más que mostrar, enseñan el mismo «Cargando…» centrado que el resto de pantallas.
 */
object LoadingCard : IconTextItem {
    override val title: String get() = "Cargando…"
    override val icon: Int get() = R.drawable.ic_sync
}
