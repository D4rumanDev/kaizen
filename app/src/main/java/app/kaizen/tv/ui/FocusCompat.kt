package app.kaizen.tv.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * A qué elemento va el foco cuando entra en este grupo (en vez de al que quede más cerca). `FocusProperties.enter` está obsoleto en favor de
 * `onEnter`, pero `onEnter` solo permite cancelar o dejar pasar, no elegir un destino; por eso el aviso se silencia aquí y no en cada pantalla.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
fun Modifier.focusEnter(target: () -> FocusRequester): Modifier = focusProperties { enter = { target() } }

/** A qué elemento va el foco al salir del grupo según la dirección ([FocusRequester.Cancel] lo retiene). Ver [focusEnter] sobre el aviso de obsoleto. */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
fun Modifier.focusExit(target: (FocusDirection) -> FocusRequester): Modifier = focusProperties { exit = { direction -> target(direction) } }
