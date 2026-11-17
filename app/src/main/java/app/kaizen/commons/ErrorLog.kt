package app.kaizen.commons

/**
 * Kaizen: sustituye a Crashlytics. Todo error de la app pasa por el gestor único [AppErrors]: se clasifica con el catálogo (E01…), se anota en la
 * sesión y se escribe en el registro de depuración con su origen y qué hacer. [area] dice de qué parte viene, si el sitio que captura lo sabe.
 */
fun logError(throwable: Throwable, area: AppErrors.Area = AppErrors.Area.APP, context: String = "") {
    AppErrors.report(throwable, area, context)
}

/** Como [logError], pero relanza la cancelación de corrutinas (no es un fallo); un timeout sí lo es. */
fun logUnlessCancelled(throwable: Throwable) {
    if (throwable is kotlinx.coroutines.CancellationException && throwable !is kotlinx.coroutines.TimeoutCancellationException)
        throw throwable
    logError(throwable)
}
