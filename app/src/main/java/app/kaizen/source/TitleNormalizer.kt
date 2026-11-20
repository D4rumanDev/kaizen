package app.kaizen.source


private val DIACRITICS = Regex("\\p{Mn}+")
private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
private val SEASON_NUMBER = Regex("(season|temporada)(\\d)")
private const val TITLE_CACHE_MAX = 4_000

/** Títulos ya normalizados: las tarjetas piden el mismo nombre una y otra vez al desplazarse. */
private val titleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Título para comparar: sin acentos, mayúsculas ni símbolos («Steins;Gate» = «steins gate»). */
internal fun normalizeTitle(name: String): String =
    titleCache[name] ?: computeNormalizedTitle(name).also {
        if (titleCache.size >= TITLE_CACHE_MAX) titleCache.clear()
        titleCache[name] = it
    }

/**
 * [normalizeTitle] más tolerante, solo para buscar: la vocal larga romanizada de cualquier forma («Hyōken» = «Hyouken» = «Hyoken»).
 * No sirve para identificar series (ahí «Moon» y «Mon» siguen siendo distintas).
 */
internal fun foldTitle(text: String): String = foldVowels(normalizeTitle(text))

/** La vocal larga romanizada de cualquier forma («ou», «oo», «uu»): el plegado de vocales de todo el código, el de las búsquedas y el del emparejado con AniList. */
internal fun foldVowels(text: String): String = text.replace("ou", "o").replace("oo", "o").replace("uu", "u")

/**
 * El primer paso común de todo normalizado de títulos: sin acentos, en minúscula, con las letras cirílicas y griegas que se ven como latinas pasadas a latinas y todo lo que
 * no es letra ni cifra ASCII convertido en un espacio (sin recortar). [normalizeTitle] sigue desde aquí (temporadas, ordinales, romanos); el emparejado con AniList
 * ([AnilistMatcher.normalize]) lo usa tal cual, con espacios.
 */
internal fun normalizedBase(name: String): String =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase()
        .map { HOMOGLYPHS[it] ?: it }.joinToString("")
        .replace(NON_ALPHANUMERIC, " ")

/** Las palabras de un texto de búsqueda: separadas por espacios, cada una ya plegada ([foldTitle]); sin las que quedan vacías. */
internal fun searchWords(text: String): List<String> = text.trim().split(Regex("""\s+""")).map(::foldTitle).filter { it.isNotEmpty() }

/** [foldTitle] sin pasar por la caché de títulos: para plegar el catálogo entero sin vaciarle la caché a las tarjetas. */
internal fun foldTitleUncached(text: String): String = foldVowels(computeNormalizedTitle(text))

/** El nombre ya plegado ([foldTitle]) contiene todas las [words] ([searchWords]); vacío no coincide con nada. */
internal fun foldedNameMatches(foldedName: String, words: List<String>): Boolean = words.isNotEmpty() && words.all { it in foldedName }

/**
 * El nombre contiene todas las palabras de [text], sin distinguir acentos, símbolos ni vocales largas. Cada palabra se busca suelta
 * en el nombre plegado (que va sin espacios), así que «magical explorer» encuentra «Magical★Explorer» y «hyouken» encuentra «Hyōken».
 */
internal fun nameMatches(name: String, text: String): Boolean = foldedNameMatches(foldTitle(name), searchWords(text))

private fun isWordChar(c: Char) = c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

/** La primera palabra de [text], cortada antes del primer símbolo («Magical★Explorer:» -> «Magical»). */
internal fun firstWord(text: String): String = text.trimStart { !isWordChar(it) }.takeWhile(::isWordChar)

/** Con qué se empieza a buscar una serie por su [name]: su primera palabra o, si es muy corta («K», «Re»), sus dos primeras. */
internal fun searchSeed(name: String): String =
    firstWord(name).takeIf { it.length >= 3 } ?: name.trim().split(Regex("""\s+""")).take(2).joinToString(" ")

/** La palabra más larga de [text] (la primera si hay empate): la más selectiva para pedirla a una web que busca mal con varias. */
internal fun longestWord(text: String): String =
    WORD_RUN.findAll(text).map { it.value }.fold("") { best, word -> if (word.length > best.length) word else best }

private fun computeNormalizedTitle(name: String): String =
    normalizedBase(name)
        .replace(SEASON_NUMBER, "$1 $2") // «Season2» = «Season 2»
        .split(' ')
        .filter { it.isNotEmpty() }
        .map { token -> if (token == "season" || token == "seasons") "temporada" else token } // «Season» = «Temporada»
        .map { token -> romanToDigits(token) ?: ORDINAL.matchEntire(token)?.groupValues?.get(1) ?: token }
        .let(::numberBeforeSeason)

/**
 * Letras cirílicas y griegas que se ven como una latina («а» cirílica = «a»; «PSYЯEN» = «Psyren»). Las webs mezclan
 * ambas y, sin esto, el mismo título no coincide entre fuentes. Todas en minúscula (se aplica tras lowercase()).
 */
private val HOMOGLYPHS: Map<Char, Char> = mapOf(
    'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p',
    'с' to 'c', 'т' to 't', 'у' to 'y', 'х' to 'x', 'я' to 'r', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's', 'ԁ' to 'd',
    'ο' to 'o', 'α' to 'a', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'χ' to 'x'
)


// «2nd», «3ra», «2nda», «1era»... -> el número.
private val WORD_RUN = Regex("[\\p{L}\\p{N}\\p{Mn}]+")
private val ORDINAL = Regex("^(\\d+)(st|nd|rd|th|ra|da|nda|era|ta)$")

/** Número romano de 2 a 4 letras (II, III, IV, VIII, XII...) en dígitos; null si el texto no lo es. */
private fun romanToDigits(token: String): String? {
    if (token.length !in 2..4 || !token.all { it in "ivx" }) return null
    val digits = token.map { when (it) { 'i' -> 1; 'v' -> 5; else -> 10 } }
    // Una cifra menor delante de otra mayor resta (IV = 4); si no, suma.
    val value = digits.indices.sumOf { i -> if (i + 1 < digits.size && digits[i] < digits[i + 1]) -digits[i] else digits[i] }
    // Solo si es la forma canónica (descarta «iiii», «vv», «ivx»...).
    return value.toString().takeIf { canonicalRoman(value) == token }
}

private fun canonicalRoman(number: Int): String {
    var rest = number
    val out = StringBuilder()
    for ((symbol, amount) in listOf("x" to 10, "ix" to 9, "v" to 5, "iv" to 4, "i" to 1)) {
        while (rest >= amount) { out.append(symbol); rest -= amount }
    }
    return out.toString()
}

/**
 * «Temporada 2», «2 Temporada» (de «2nd Season»), «II» y «2nda» quedan todos como «2»: junto a un número, la palabra
 * «temporada» sobra. Sin número («X Season») se conserva. Une las palabras.
 */
private fun numberBeforeSeason(tokens: List<String>): String {
    val out = ArrayList<String>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        val next = tokens.getOrNull(index + 1)
        when {
            token != "temporada" -> { out += token; index++ }
            next != null && next.all { it.isDigit() } -> { out += next; index += 2 } // «temporada 2»
            out.lastOrNull()?.all { it.isDigit() } == true -> index++ // «2 temporada»
            else -> { out += token; index++ }
        }
    }
    return out.joinToString("")
}

/** Dos títulos normalizados son el mismo: iguales, o uno es el comienzo del otro (la web recorta los muy largos). */
internal fun sameTitle(a: String, b: String): Boolean =
    a == b || (minOf(a.length, b.length) >= 40 && (a.startsWith(b) || b.startsWith(a)))
