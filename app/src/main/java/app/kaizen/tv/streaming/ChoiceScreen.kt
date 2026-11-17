@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.streaming

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import app.kaizen.tv.ui.rememberAutoFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Una opción de [ChoiceScreen]. Con [enabled] falso sale al final en grises (mismo estilo que una fuente deshabilitada), no
 * se puede elegir y [note] dice por qué («En pausa hasta 22:15»).
 */
data class ChoiceOption(
    val id: Int,
    val label: String,
    val enabled: Boolean = true,
    val note: String? = null,
    /** Encabezado bajo el que sale (la fuente, en la lista de servidores); null = sin grupo. */
    val group: String? = null,
    /** Se puede elegir, pero venía fallando: sale con un icono de aviso delante, tras las que funcionan. */
    val warning: Boolean = false
) {
    val text: String get() {
        val body = if (note.isNullOrBlank()) label else "$label · $note"
        return if (warning) "$WARNING_ICON $body" else body
    }

    companion object {
        const val WARNING_ICON = "⚠"

        /**
         * Orden de la pantalla: los grupos en el orden en que aparecen y, dentro de cada uno, primero las que funcionan y detrás las
         * deshabilitadas o con aviso (el orden entre ellas se conserva). Sin grupos, las que funcionan primero y detrás las deshabilitadas, con los avisos al final.
         */
        fun arranged(options: List<ChoiceOption>): List<ChoiceOption> =
            options.groupBy { it.group }.values.flatMap { group -> group.sortedBy { !it.enabled || it.warning } }
    }
}

/** Lista de opciones a pantalla completa (servidor, calidad, idioma): [onChosen] recibe el id de la elegida. */
@Composable
internal fun ChoiceScreen(title: String, subtitle: String, options: List<ChoiceOption>, onChosen: (Int) -> Unit) {
    val first = rememberAutoFocus()
    Box(Modifier.fillMaxSize().background(SurfaceDark), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = Text24, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = White70, fontSize = Text16, modifier = Modifier.padding(bottom = 12.dp))
            val ordered = ChoiceOption.arranged(options)
            ordered.forEachIndexed { index, option ->
                // Encabezado cada vez que cambia el grupo (la fuente).
                if (option.group != null && option.group != ordered.getOrNull(index - 1)?.group) {
                    Text(option.group, color = AccentLight, fontSize = Text16, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp))
                }
                KaizenButton(
                    option.text,
                    onClick = { onChosen(option.id) },
                    modifier = Modifier.fillMaxWidth().then(if (index == 0 && option.enabled) Modifier.focusRequester(first) else Modifier),
                    fontSize = Text18,
                    enabled = option.enabled
                )
            }
        }
    }
}
