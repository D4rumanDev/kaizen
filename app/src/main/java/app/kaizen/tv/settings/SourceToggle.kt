package app.kaizen.tv.settings

import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.appString
import app.kaizen.retrofit.Repository
import app.kaizen.tv.ui.KaizenToast

/**
 * Activa o desactiva una fuente (el menú «Fuentes»). Siempre queda al menos una activa; devuelve si se aplicó.
 * El catálogo interno se recorre solo (o a mano) desde Configuración › Catálogo.
 */
internal fun setSourceActive(id: String, on: Boolean): Boolean {
    val chosen = if (on) KaizenSettings.sourceIds + id else KaizenSettings.sourceIds - id
    if (chosen.isEmpty()) {
        KaizenToast.toast(appString(R.string.ui_ninguna_fuente_activa_debe_haber_al_menos_una))
        return false
    }
    KaizenSettings.sourceIds = chosen
    Repository.reloadRecents()
    return true
}
