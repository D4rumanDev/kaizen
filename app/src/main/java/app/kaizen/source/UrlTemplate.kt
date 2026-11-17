package app.kaizen.source

/** Rellena una plantilla de URL de un fichero de fuente: {base} y los marcadores de [values] (p. ej. "slug" to "x" para {slug}). */
internal fun fillTemplate(base: String, template: String, vararg values: Pair<String, String>): String =
    values.fold(template.replace("{base}", base)) { text, (key, value) -> text.replace("{$key}", value) }
