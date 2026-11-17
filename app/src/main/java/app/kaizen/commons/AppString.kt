package app.kaizen.commons

import androidx.annotation.StringRes
import app.kaizen.App

/** Un texto de la interfaz (res/values/ui_strings.xml) desde código que no es un @Composable; [args] rellenan sus %1$s, %2$d… */
internal fun appString(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) App.context.getString(id) else App.context.getString(id, *args)
