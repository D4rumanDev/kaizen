package app.kaizen.tv.ui.theme

import androidx.compose.ui.graphics.Color

// Colores de la interfaz que se repiten. WhiteNN es blanco con NN % de opacidad (textos y superficies sobre fondo oscuro).
internal val White80 = Color(0xCCFFFFFF)
internal val White70 = Color(0xB3FFFFFF)
internal val White60 = Color(0x99FFFFFF)
internal val White50 = Color(0x80FFFFFF)
internal val White40 = Color(0x66FFFFFF)
internal val White20 = Color(0x33FFFFFF)
internal val White10 = Color(0x1AFFFFFF)

/** Datos destacados (año, detalle de una serie). */
internal val Highlight = Color(0xFFFFD54F)

/** Fondo del menú lateral y de las pantallas completas. */
internal val SurfaceDark = Color(0xFF0B1729)

/** Fondo de las tarjetas y los bloques de estadísticas. */
internal val SurfaceCard = Color(0xFF101E33)

/** Velo oscuro detrás de ventanas y degradados sobre el vídeo. */
internal val Scrim = Color(0xCC000000)

/** Estado de las fuentes y de las fases de carga: bien, aviso y caído. */
internal val StatusOk = Color(0xFF55C994)
internal val StatusWarn = Color(0xFFF3B95F)
internal val StatusDown = Color(0xFFFF647C)

/** Fondo de logos y huecos de imagen. */
internal val SurfaceMuted = Color(0xFF1C2D47)

/** Azul de acento de la app y su versión clara (etiquetas, enlaces, textos destacados sobre fondo oscuro). */
internal val Accent = Color(0xFF2675D8)
internal val AccentLight = Color(0xFF48A0FF)

/** Estados de lo que se puede activar (secciones, botones): mismo criterio en la lista de organizar y en las vistas previas. */
internal val ActiveCard = Color(0xFF1E3A5C)
internal val InactiveCard = Color(0xFF14243A)
internal val InactiveContent = Color(0xFFFF8A80)
internal val GrabbedCard = Color(0xFFFFB300)
internal val GrabbedContent = Color(0xFF2B1F00)

/** Fondo de los paneles y ventanas (diálogos, ajustes). */
internal val PanelColor = Color(0xF20B1729)

/** Fondo de una tarjeta y el suyo con el foco. */
internal val CardColor = SurfaceCard
internal val CardColorFocused = Color(0xFF172A43)

/** Nota de una serie (estrella). */
internal val ScoreColor = Color(0xFFFFC107)

/** Nota en porcentaje: alta, media y baja (fondo de la etiqueta; el texto va en blanco, salvo sobre el ámbar). */
internal val ScoreGood = Color(0xFF2E7D32)
internal val ScoreFair = Color(0xFFF9A825)
internal val ScoreBad = Color(0xFFC62828)

/** Capítulo visto (check) y fallos de servidores en estadísticas. */
internal val SeenGreen = StatusOk
internal val FailureRed = StatusDown

/** Negro con NN % de opacidad: velos y fondos de marcas sobre imágenes o vídeo (como WhiteNN). */
internal val Black90 = Color(0xE6000000)
internal val Black67 = Color(0xAA000000)
internal val Black60 = Color(0x99000000)
internal val Black50 = Color(0x80000000)

/** Fondos de los avisos flotantes: pastilla del reproductor y notificaciones. */
internal val HintBackground = Color(0xD90B1729)
internal val ToastBackground = Color(0xE6101E33)

/** Naranja de Cloudflare (su logo en Fuentes). */
internal val CloudflareOrange = Color(0xFFF38020)

/** Paleta base (azul oscuro): fondo de las pantallas, borde de separación, texto secundario y deshabilitado, favorito y barra de progreso. */
internal val Background = Color(0xFF080F1D)
internal val Border = Color(0xFF263750)
internal val TextSecondary = Color(0xFFA9B8CC)
internal val ProgressTrack = Color(0xFF34445B)
