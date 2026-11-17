package app.kaizen.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Esquinas redondeadas que se repiten (las de un solo uso se quedan donde están).
internal val ShapeSm = RoundedCornerShape(3.dp)
internal val ShapeMd = RoundedCornerShape(5.dp)
internal val ShapeLg = RoundedCornerShape(6.dp)
internal val ShapeXl = RoundedCornerShape(8.dp)
internal val ShapePill = RoundedCornerShape(7.dp) // botones y chips: rectángulos suaves, como Prime Video

/** Esquinas de la imagen dentro de una tarjeta (algo menos redondas que la tarjeta, que las rodea con [CardInset]). */
internal val CardImageShape = ShapeMd
/** Portada de las vistas previas de los diálogos de revisión y de separar fichas. */
internal val PreviewPosterShape = ShapeMd

/** Radios del sistema de diseño: carátula 5 dp, tarjeta 7 dp y etiqueta 3 dp, muy suaves como Prime Video. */
internal val CardShape = ShapePill
internal val BadgeShape = ShapeSm
