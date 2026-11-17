package app.kaizen.tv.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Alto mínimo de una tarjeta de icono y texto: icono de 48 dp, dos líneas de texto y sus márgenes.
internal val IconCardMinHeight = 132.dp

// Iconos.
internal val IconSm = 22.dp
internal val IconLg = 28.dp
internal val IconXl = 32.dp
internal val PlayerIcon = IconXl

// Tarjetas. Un ancho base (lo escala «Tamaño de las tarjetas»), la proporción de la portada y los márgenes, comunes a todas.
internal val CardBaseWidth = 180.dp // = PosterPreferredWidth
/** Alto de una tarjeta de serie de ancho [width]: carátula 2:3 ([PosterAspectRatio]) dentro de los márgenes del foco, más el texto de debajo ([CardTextAreaHeight]). */
internal fun posterHeight(width: Dp): Dp = (width - CardInset * 2) / PosterAspectRatio + CardTextAreaHeight + CardInset * 2
/** Margen entre el borde de una tarjeta y su contenido; el texto añade [CardTextInset] a los lados. Igual en todas las tarjetas. */
internal val CardInset = 6.dp
internal val CardTextInset = 8.dp
/** Por debajo de este ancho la portada es tan baja que el panel de datos solo deja sitio para tres líneas de sinopsis. */
internal val NarrowCardWidth = 170.dp
/** Tarjeta de capítulo (imagen ancha 16:9). */
internal val EpisodeCardWidth = 220.dp // = EpisodeThumbnailWidth
internal val EpisodeImageHeight = 123.75.dp // 16:9
/** Tarjetas pequeñas de las relaciones de la ficha. */
internal val RelatedCardWidth = 130.dp
/** Ancho mínimo de las tarjetas del selector de fichas (diálogo). */
internal val PickCardWidth = 320.dp
/** Portada de las vistas previas de los diálogos de revisión y de separar fichas. */
internal val PreviewPosterWidth = 150.dp
internal val PreviewPosterHeight = 210.dp

// Pantalla.
/** Ancho del menú lateral cerrado: la rejilla se calcula con él para no moverse al abrirlo. */
internal val ClosedMenuWidth = 80.dp
/** Separación del aviso flotante al borde inferior. */
internal val ToastBottomPadding = 36.dp

internal const val PosterAspectRatio = 2f / 3f
/** Columnas que llenan la fila con «Tamaño de las tarjetas» ×1: el ancho de la carátula sale del ancho disponible, así que no depende de los dp de cada pantalla. */
internal const val PosterTargetColumns = 5
/** Ancho mínimo de una carátula en una rejilla estrecha (diálogos): por debajo, caben menos columnas. */
internal val PosterNarrowestWidth = 130.dp
/** Separación de las carátulas, y márgenes del contenido. */
internal val PosterHorizontalSpacing = 14.dp
internal val PosterVerticalSpacing = 14.dp
internal val ContentPaddingHorizontal = 36.dp
internal val ContentPaddingVertical = 28.dp
/** Alto del texto bajo la carátula (solo el título, de una línea). */
internal val CardTextAreaHeight = 32.dp
/** Ficha de serie. */
internal val DetailPosterWidth = 130.dp
internal val DetailHeaderMinHeight = 214.dp
internal val DetailSectionSpacing = 12.dp
/** Espacio bajo la última fila de la ficha: la fila de recomendadas no queda pegada al borde de la pantalla. */
internal val DetailBottomPadding = 120.dp
