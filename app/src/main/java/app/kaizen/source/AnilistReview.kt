package app.kaizen.source

/** Una serie local cuyo mejor candidato de AniList es dudoso: espera a que el usuario diga si es ese anime. */
internal class PendingSeries(val fichas: List<Pair<String, String>>, val anilistId: Long, val nivel: String, val score: Double, val priority: Int)

/** Cuánto coincide un dato de la serie local con el del anime de AniList, para pintarlo (verde, ámbar, rojo o gris). */
internal enum class Match { SAME, CLOSE, DIFFERENT, UNKNOWN }

/** Comparaciones de la revisión: cada una dice si el dato de las fuentes y el de AniList coinciden. */
internal object AnilistCompare {
    /** El parecido del título de las fuentes con los de AniList (el del emparejado, con el matcher de ahora); null sin datos. */
    fun titleScore(local: String?, candidate: FichaData?): Double? {
        if (local.isNullOrBlank() || candidate == null) return null
        return AnilistMatcher.score(
            AnilistMatcher.normalize(local), AnilistMatcher.Candidate(candidate.anilistId ?: return null, candidate.formato, candidate.romaji, candidate.english, candidate.native, emptyList())
        )
    }

    fun title(local: String?, candidate: FichaData?): Match {
        val score = titleScore(local, candidate) ?: return Match.UNKNOWN
        return when { score >= 0.95 -> Match.SAME; score >= 0.7 -> Match.CLOSE; else -> Match.DIFFERENT }
    }

    /** Los géneros de las fuentes (slugs) frente a los de AniList: se comparan los que AniList tiene, por cuántos de los más cortos coinciden. */
    fun genres(local: Collection<String>, candidate: Collection<String>): Match {
        val mine = GenreNames.anilistSet(local)
        if (mine.isEmpty() || candidate.isEmpty()) return Match.UNKNOWN
        val shared = mine.count { it in candidate }.toDouble() / minOf(mine.size, candidate.size)
        return when { shared >= 0.6 -> Match.SAME; shared >= 0.3 -> Match.CLOSE; else -> Match.DIFFERENT }
    }

    /** «En emisión» o «finalizada» de las fuentes frente al estado de AniList; sin dato o con un estado intermedio, desconocido. */
    fun status(localAiring: Boolean?, candidateStatus: String?): Match = when {
        localAiring == null -> Match.UNKNOWN
        candidateStatus == "RELEASING" -> if (localAiring) Match.SAME else Match.DIFFERENT
        candidateStatus == "FINISHED" -> if (localAiring) Match.DIFFERENT else Match.SAME
        else -> Match.UNKNOWN
    }

    /** El estudio de las fuentes frente a los de AniList (sin mirar mayúsculas ni signos). */
    fun studio(local: String?, candidate: String?): Match {
        if (local.isNullOrBlank() || candidate.isNullOrBlank()) return Match.UNKNOWN
        // «8bit» y «8-bit» son el mismo estudio: se comparan sin espacios.
        val a = AnilistMatcher.normalize(local).replace(" ", ""); val b = AnilistMatcher.normalize(candidate).replace(" ", "")
        return if (a == b || a in b || b in a) Match.SAME else Match.DIFFERENT
    }

    /**
     * El parecido de la serie con todos los datos: el del título (peso 3) y los que coinciden o no en año, episodios, géneros, estado y
     * estudio (peso 1 cada uno, la mitad si solo se parecen). Lo que no se sabe no cuenta. Entre 0 y 1.
     */
    fun overall(title: Double, others: List<Match>): Double {
        val known = others.filter { it != Match.UNKNOWN }
        val t = title.coerceIn(0.0, 1.0)
        val points = known.sumOf { when (it) { Match.SAME -> 1.0; Match.CLOSE -> 0.5; else -> 0.0 } }
        return (3 * t + points) / (3 + known.size)
    }

    /** Lo que se hace con una serie dudosa por lo que dicen todos sus datos: darla por buena, descartarla o preguntar. */
    enum class Auto { ACCEPT, REJECT, ASK }

    /**
     * Se da por buena sin preguntar solo si el año y los episodios coinciden, ningún dato difiere, al menos tres coinciden y el título y el
     * parecido total son altos. Se descarta si al menos dos datos difieren y el parecido total es bajo. El resto lo decide el usuario.
     */
    fun auto(title: Double, year: Match, episodes: Match, others: List<Match>, format: Match = Match.UNKNOWN): Auto {
        // El formato solo puede frenar (distinto, o de otra clase) y nunca sumar: casi todo es «TV» y que coincida no prueba nada.
        val strict = if (format == Match.SAME) Match.UNKNOWN else format
        val known = (listOf(year, episodes, strict) + others).filter { it != Match.UNKNOWN }
        val different = known.count { it == Match.DIFFERENT }
        val same = known.count { it == Match.SAME }
        val overall = overall(title, listOf(year, episodes, strict) + others)
        return when {
            year == Match.SAME && episodes == Match.SAME && different == 0 && same >= 3 && title >= 0.7 && overall >= 0.9 -> Auto.ACCEPT
            known.size >= 2 && different >= 2 && overall < 0.4 -> Auto.REJECT
            else -> Auto.ASK
        }
    }

    /** El formato de la web frente al de AniList: igual, de la misma clase (TV y TV_SHORT, OVA y especial) o de otra (una película no es una serie). */
    fun format(local: String?, candidate: String?): Match = when {
        local == null || candidate == null -> Match.UNKNOWN
        local == candidate -> Match.SAME
        AnilistMatcher.formatClass(local) == AnilistMatcher.formatClass(candidate) -> Match.CLOSE
        else -> Match.DIFFERENT
    }

    /** Hasta cuántos episodios llega, como mucho, una OVA o un especial (lo normal son 1 a 4 y raro pasar de 6); las películas son 1. */
    const val SHORT_MAX = 6

    /** Desde cuántos episodios es ya una temporada de serie (TV u ONA larga): un cour son 12 o 13. */
    const val SEASON_MIN = 12

    /**
     * Si los episodios de las webs ([local]) y los emitidos de AniList ([aired]) difieren tanto que no pueden ser el mismo anime: el mayor llega a una temporada (desde [SEASON_MIN]) y el
     * menor cabe en una OVA o un especial (hasta [SHORT_MAX]) o es un tercio o menos. Con números pequeños (1 frente a 3, 2 frente a 6: una película y una OVA corta) no se decide por los episodios. El doble exacto no cuenta (las webs juntan a menudo dos cours: 24 frente a 12), y si la web
     * está en emisión y tiene menos que AniList solo puede ser que aún no los haya subido todos ([localAiring]).
     */
    fun episodesFarApart(local: Int?, aired: Int?, localAiring: Boolean?): Boolean {
        if (local == null || aired == null || local <= 0 || aired <= 0) return false
        val lo = minOf(local, aired)
        val hi = maxOf(local, aired)
        if (hi == 2 * lo) return false
        if (local < aired && localAiring == true) return false
        return hi >= SEASON_MIN && (lo <= SHORT_MAX || hi >= 3 * lo)
    }

    /** Todo lo que se compara de una serie dudosa, calculado una vez en [compare]: cada dato, el parecido total y qué hacer con ella; la pantalla solo lo pinta. */
    class Compared(
        val aired: Int?, val year: Match, val episodes: Match, val genres: Match, val status: Match, val studio: Match, val format: Match, val mal: Match,
        val sharedName: String?, val overall: Double?, val auto: Auto?, val duration: Match = Match.UNKNOWN
    )

    /**
     * Compara los datos que dan las webs ([localYear]…, [names] = sus otros nombres) con la ficha de AniList [candidate] (null: aún no está, todo «sin dato»). Es el único sitio que
     * decide qué datos cuentan en el parecido total ([overall], con el [title] ya calculado) y en la resolución sin preguntar ([auto]); un dato nuevo se añade aquí y en ningún otro.
     * [overall] es null sin [title], y [auto] sin [title] o sin [candidate]. Con los episodios muy distintos ([episodesFarApart]) la serie se descarta sin más (REJECT): una OVA no es la serie; salvo que el `idMal` coincida, que es la mejor prueba y manda.
     */
    fun compare(
        candidate: FichaData?, title: Double?, localYear: Int?, localEpisodes: Int?, localGenres: Collection<String>, localAiring: Boolean?, localStudio: String?, localFormat: String?,
        localMal: Long?, names: List<String>, localDuration: Int? = null
    ): Compared {
        val aired = airedEpisodes(candidate)
        val year = year(localYear, candidate?.anio)
        val episodes = episodes(localEpisodes, aired)
        val genres = genres(localGenres, candidate?.generos.orEmpty())
        val status = status(localAiring, candidate?.estado)
        val studio = studio(localStudio, candidate?.estudio)
        val format = format(localFormat, candidate?.formato)
        val mal = mal(localMal, candidate?.idMal)
        val duration = duration(localDuration, localEpisodes, candidate?.duracion, candidate?.episodios)
        val shared = candidate?.let { sharedName(names, it.nombres) }
        // Un nombre compartido suma como coincidencia; no tenerlo no resta (cada web pone los nombres que quiere).
        val others = listOf(genres, status, studio, mal, if (shared != null) Match.SAME else Match.UNKNOWN)
        // La duración (solo de fichas de un episodio) suma o resta en el parecido total, pero es un dato débil (hay muchos OVA de 24-25 min): en [auto] solo puede frenar (distinta)
        // y nunca completar las coincidencias que hacen falta para darla por buena; tampoco descarta sola (hacen falta dos que difieran).
        val forAuto = others + if (duration == Match.SAME) Match.UNKNOWN else duration
        return Compared(
            aired, year, episodes, genres, status, studio, format, mal, shared,
            overall = title?.let { overall(it, listOf(year, episodes, format, duration) + others) },
            auto = if (title == null || candidate == null) null else if (mal != Match.SAME && episodesFarApart(localEpisodes, aired, localAiring)) Auto.REJECT else auto(title, year, episodes, forAuto, format),
            duration = duration
        )
    }

    /**
     * La duración en minutos de la ficha de la web frente a la de AniList, solo si las dos son de un único episodio (una película, un OVA o un especial): ahí los episodios no
     * distinguen (1 frente a 1) y la duración sí (90 min frente a 25). Una serie larga tiene la duración de un capítulo y no dice nada del conjunto. Las webs redondean: coinciden dentro
     * de 5 min o el 10 %, se parecen dentro de 10 min o el 20 %. Una web que no da la duración, o una ficha sin un único episodio, queda en [Match.UNKNOWN]: no suma ni resta.
     */
    fun duration(local: Int?, localEpisodes: Int?, candidate: Int?, candidateEpisodes: Int?): Match {
        if (local == null || candidate == null || local <= 0 || candidate <= 0 || localEpisodes != 1 || candidateEpisodes != 1) return Match.UNKNOWN
        val gap = kotlin.math.abs(local - candidate)
        val big = maxOf(local, candidate)
        return when {
            gap <= maxOf(5, big / 10) -> Match.SAME
            gap <= maxOf(10, big / 5) -> Match.CLOSE
            else -> Match.DIFFERENT
        }
    }

    /** El nombre sin mayúsculas ni signos, conservando las letras de cualquier alfabeto: `AnilistMatcher.normalize` deja solo a-z y 0-9 y un nombre en japonés quedaría vacío o igual a otro con el mismo resto latino. */
    private fun nameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** Letras y dígitos mínimos de un nombre para que cuente como coincidencia: «2», «OVA» o «Final» los comparten series distintas y no prueban nada. */
    const val MIN_NAME_KEY = 6

    /**
     * El nombre de AniList (romaji, inglés, nativo o sinónimo) que es igual, sin mirar mayúsculas ni signos, a algún otro nombre que da la web ([local], sin su título principal);
     * null si no hay ninguno. Que no coincidan no prueba nada (cada web pone los nombres que quiere), así que solo sirve para confirmar, nunca para descartar ni para puntuar.
     */
    fun sharedName(local: List<String>, candidate: List<String>): String? {
        val wanted = local.map(::nameKey).filter { it.length >= MIN_NAME_KEY }.toSet()
        if (wanted.isEmpty()) return null
        return candidate.firstOrNull { nameKey(it) in wanted }
    }

    /** El id de MyAnimeList que da la web frente al de AniList: si coinciden es el mismo anime; si no, no. */
    fun mal(local: Long?, candidate: Long?): Match = when {
        local == null || candidate == null -> Match.UNKNOWN
        local == candidate -> Match.SAME
        else -> Match.DIFFERENT
    }

    fun year(local: Int?, candidate: Int?): Match = when {
        local == null || candidate == null -> Match.UNKNOWN
        local == candidate -> Match.SAME
        LinkEvidence.yearsClose(local, candidate) -> Match.CLOSE
        else -> Match.DIFFERENT
    }

    /** Los episodios que AniList ya ha emitido (si sigue en emisión, hasta el anterior al próximo) o, si no, los totales. */
    fun airedEpisodes(candidate: FichaData?): Int? =
        candidate?.proximoEpisodio.takeIf { candidate?.estado == "RELEASING" }?.let { it - 1 } ?: candidate?.episodios

    fun episodes(local: Int?, aired: Int?): Match = when {
        local == null || aired == null || aired <= 0 -> Match.UNKNOWN
        local == aired -> Match.SAME
        kotlin.math.abs(local - aired) <= 1 -> Match.CLOSE
        // Las fuentes juntan a menudo las dos partes (cours) que AniList cuenta aparte: 24 frente a 12.
        local == 2 * aired -> Match.CLOSE
        else -> Match.DIFFERENT
    }
}

/**
 * Revisión de los emparejados dudosos de AniList (ver `docs/wiki/emparejado-anilist.md`): lista las series con candidato dudoso, pide a AniList
 * el candidato para que el usuario lo compare y guarda su decisión. Lo que decide el usuario se escribe con `origen = usuario`: ningún trabajo
 * automático lo vuelve a tocar.
 */
internal object AnilistReview {
    /** Las series sin enlace aplicado y con un candidato dudoso (el de mayor puntuación), con favoritos y vistos primero y las más parecidas antes. */
    fun pending(series: Collection<SeriesWork>, row: (String, String) -> AnimeFuente?): List<PendingSeries> =
        series.mapNotNull { work ->
            val rows = rowsOf(work, row)
            if (rows.any { it.estado == AnimeFuente.APLICADO }) return@mapNotNull null
            // Los que ya se guardaron con un parecido tan bajo (antes todo lo no descartado era dudoso) no se preguntan.
            val best = rows.filter { it.estado == AnimeFuente.PENDIENTE && it.anilistId != null && it.puntuacion >= AnilistMatcher.DUDOSO_FROM }
                .maxByOrNull { it.puntuacion } ?: return@mapNotNull null
            val anilistId = best.anilistId ?: return@mapNotNull null
            PendingSeries(work.fichas, anilistId, best.nivel, best.puntuacion, work.priority)
        }.sortedWith(compareByDescending<PendingSeries> { it.priority }.thenByDescending { it.score })

    /**
     * Las series sin enlace cuyo candidato es un anime que ya está enlazado ([applied], por decisión del usuario o del emparejado): es el
     * mismo anime en otra ficha, así que se enlazan con él (origen auto, nivel «mismo-id»). Devuelve las filas a guardar; no toca lo del usuario.
     *
     * Una serie que ya tiene alguna ficha enlazada no se adopta entera: solo se completan sus fichas por revisar cuyo candidato es uno de los
     * animes que enlazan sus hermanas ([JoinRules.completions]).
     *
     * [acceptable] juzga la fila tal como se guardaría: si el comprobador de enlaces la quitaría después (una película enlazada a una serie de TV,
     * un anime que no se parece a la ficha), no se adopta, para no enlazar y desenlazar en cada arranque.
     */
    fun adoptions(series: Collection<SeriesWork>, row: (String, String) -> AnimeFuente?, applied: Set<Long>, at: Long, acceptable: (AnimeFuente) -> Boolean = { true }): List<AnimeFuente> =
        series.flatMap { work ->
            val rows = rowsOf(work, row)
            val inSeries = rows.filter { it.estado == AnimeFuente.APLICADO }.mapNotNull { it.anilistId }.toSet()
            if (inSeries.isNotEmpty()) return@flatMap JoinRules.completions(work, row, inSeries, at, acceptable)
            // El mejor candidato de la serie, como en [pending]: solo se adopta si ese ya está enlazado y es lo bastante parecido.
            val best = rows.filter { it.estado == AnimeFuente.PENDIENTE && it.anilistId != null }.maxByOrNull { it.puntuacion }
                ?.takeIf { it.anilistId in applied && it.puntuacion >= JoinRules.CONFIRMED_FROM } ?: return@flatMap emptyList()
            notDecidedByUser(work.fichas, row).map { (f, s) -> AnimeFuente(f, s, best.anilistId, AnimeFuente.APLICADO, AnimeFuente.AUTO, "mismo-id", best.puntuacion, at) }
        }

    /** Las filas guardadas de las fichas de la serie (las que no tienen fila no salen). */
    private fun rowsOf(work: SeriesWork, row: (String, String) -> AnimeFuente?): List<AnimeFuente> = work.fichas.mapNotNull { (f, s) -> row(f, s) }

    /** Las fichas [fichas] cuya fila no es una decisión del usuario: lo que decidió el usuario no lo toca ningún trabajo automático. */
    private fun notDecidedByUser(fichas: List<Pair<String, String>>, row: (String, String) -> AnimeFuente?): List<Pair<String, String>> =
        fichas.filter { (f, s) -> row(f, s)?.origen != AnimeFuente.USUARIO }

    /** El anime candidato tal como lo da AniList (una petición, con el ritmo de [AnilistHttp]); null si no lo da. */
    suspend fun candidate(anilistId: Long): AnilistFichaRaw? {
        candidates[anilistId]?.let { return it }
        return AnilistClient(AnilistHttp::post).fichas(listOf(anilistId)).firstOrNull()?.also {
            // Pocas a la vez: son las de la serie que se mira y las que se adelantan (ver [prefetch]).
            if (candidates.size >= MAX_CANDIDATES) candidates.clear()
            candidates[anilistId] = it
        }
    }

    private val candidates = java.util.concurrent.ConcurrentHashMap<Long, AnilistFichaRaw>()
    private const val MAX_CANDIDATES = 12

    /** Descarga con antelación el candidato de AniList de las próximas series, para que al decidir la siguiente salga sin esperar a la red. */
    suspend fun prefetch(anilistIds: List<Long>) {
        for (id in anilistIds) app.kaizen.commons.runCatchingCoop { candidate(id) }
    }

    /**
     * Guarda la decisión del usuario para todas las fichas de la serie: [same] la enlaza con el candidato (y guarda su ficha ya, si se
     * descargó para compararlo); si no, queda «sin resultado». En los dos casos `origen = usuario`. Devuelve lo que había antes en cada
     * ficha, para poder deshacerlo con [undo].
     */
    fun decide(series: PendingSeries, same: Boolean, fuentes: AnimeFuenteDao, fichas: AnimeFichaDao, raw: AnilistFichaRaw?, at: Long): List<AnimeFuente?> {
        val before = series.fichas.map { (fuente, slug) -> fuentes.get(fuente, slug) }
        fuentes.put(series.fichas.map { (fuente, slug) ->
            if (same) AnimeFuente(fuente, slug, series.anilistId, AnimeFuente.APLICADO, AnimeFuente.USUARIO, "manual", 1.0, at)
            else AnimeFuente(fuente, slug, null, AnimeFuente.SIN_RESULTADO, AnimeFuente.USUARIO, "manual", 0.0, at)
        })
        if (same && raw != null) fichas.put(listOf(raw.toFicha(at, fichas.get(raw.id)?.es)))
        return before
    }

    /**
     * Da por buena una serie sin preguntar (ver [AnilistCompare.auto]): enlaza todas sus fichas con el candidato como decisión automática
     * (origen auto, nivel «datos») y guarda la ficha de AniList. No toca lo que decidió el usuario.
     */
    fun accept(series: PendingSeries, score: Double, fuentes: AnimeFuenteDao, fichas: AnimeFichaDao, raw: AnilistFichaRaw, at: Long) {
        fuentes.put(notDecidedByUser(series.fichas, fuentes::get).map { (fuente, slug) -> AnimeFuente(fuente, slug, series.anilistId, AnimeFuente.APLICADO, AnimeFuente.AUTO, "datos", score, at) })
        fichas.put(listOf(raw.toFicha(at, fichas.get(raw.id)?.es)))
    }

    /** Guarda como «sin resultado» (origen auto, lo puede volver a mirar el emparejado) una serie cuyo candidato ya no pasa el corte del matcher. */
    fun discard(series: PendingSeries, fuentes: AnimeFuenteDao, at: Long) {
        fuentes.put(notDecidedByUser(series.fichas, fuentes::get).map { (fuente, slug) -> AnimeFuente(fuente, slug, null, AnimeFuente.SIN_RESULTADO, AnimeFuente.AUTO, "descartado", 0.0, at) })
    }

    /** Deshace [decide]: cada ficha vuelve a lo que tenía ([before]); la que no tenía fila vuelve a quedar como candidato dudoso. */
    fun undo(series: PendingSeries, before: List<AnimeFuente?>, fuentes: AnimeFuenteDao, at: Long) {
        fuentes.put(series.fichas.mapIndexed { i, (fuente, slug) ->
            before.getOrNull(i) ?: AnimeFuente(fuente, slug, series.anilistId, AnimeFuente.PENDIENTE, AnimeFuente.AUTO, series.nivel, series.score, at)
        })
    }
}

/**
 * Comprobación de un enlace con AniList ya guardado: el título de la ficha de la fuente (su slug) tiene que parecerse a algún nombre del
 * anime enlazado (romaji, inglés, nativo o sinónimos, como en el emparejado). Un enlace que no pasa viene de una serie mezclada o de un
 * emparejado antiguo: no se usa para unir series y el emparejado lo rehace.
 */
internal object AnilistLinkCheck {
    /** Parecido mínimo del slug con el título del anime; los enlaces buenos quedan muy por encima y los equivocados cerca de cero. */
    const val MIN_SIMILARITY = 0.35

    private val SLUG_NOISE = setOf("latino", "castellano", "sub", "espanol", "online", "hd")

    /** El slug de una ficha como título comparable: sin guiones ni palabras de versión («latino», «castellano»…). */
    internal fun slugTitle(slug: String): String =
        AnilistMatcher.normalize(slug.replace('-', ' ')).split(' ').filter { it.isNotEmpty() && it !in SLUG_NOISE }.joinToString(" ")

    /**
     * Si el [slug] se parece a algún nombre de [candidate] y ese nombre es de la misma temporada: «x-2nd-season» no es el anime «X» ni «x» es
     * «X 2nd Season», aunque el título casi coincida (la temporada distinta solo resta en el emparejado, y aquí lo único que sumaba era el parecido).
     */
    fun fits(slug: String, candidate: AnilistMatcher.Candidate): Boolean {
        val title = slugTitle(slug)
        val season = AnilistMatcher.split(title).season
        // «fruits-basket-2019» no es el anime de 2001: un año del slug que no cuadra (con un año de margen) con el del anime lo rechaza.
        val year = AnilistMatcher.split(title).year
        if (!LinkEvidence.yearsClose(year, candidate.year)) return false
        val sameSeason = candidate.names.filter { AnilistMatcher.split(AnilistMatcher.normalize(it)).season == season }
        if (sameSeason.isEmpty()) return false
        return AnilistMatcher.score(title, AnilistMatcher.Candidate(candidate.id, candidate.format, sameSeason.first(), null, null, sameSeason.drop(1), candidate.year)) >= MIN_SIMILARITY
    }

    /** Si el slug dice película, OVA o especial y el anime enlazado es una serie de TV (ver [AnilistMatcher.formatClash]); con los nombres guardados basta, el formato va en [titles]. */
    internal fun formatClashes(slug: String, titles: AnimeFichaDao.Titles?): Boolean {
        titles ?: return false
        return AnilistMatcher.formatClash(slugTitle(slug), titles.formato, listOfNotNull(titles.tituloRomaji, titles.tituloIngles))
    }

    /** Lo que hay que rehacer: las [rows] (sin repetir), de ellas [weak] «probable» aplicados solo por el nombre y [format] «sin resultado» de película, OVA o especial. */
    class Redo(val rows: List<AnimeFuente>, val weak: Int, val format: Int)

    /**
     * Las filas automáticas que hay que quitar para que el emparejado las rehaga: las que no valen ([inconsistent]) y, una sola vez cada migración (los indicadores [withEvidence] y
     * [withFormat]), los «probable» aplicados solo por el nombre (criterio por pruebas) y las «sin resultado» cuyo slug dice película, OVA o especial (búsqueda por formato).
     * Nunca una fila del usuario ni un descarte del usuario («descartado») ni una mezcla («mezcla»).
     */
    fun redo(all: Collection<AnimeFuente>, titles: Map<Long, AnimeFichaDao.Titles>, ficha: (Long) -> AnimeFicha?, withEvidence: Boolean, withFormat: Boolean, webMal: (String, String) -> Long? = { _, _ -> null }): Redo {
        val bad = LinkedHashMap<Pair<String, String>, AnimeFuente>()
        inconsistent(all, titles, webMal, ficha).forEach { bad[it.fuente to it.slug] = it }
        var weak = 0
        var format = 0
        for (it in all) {
            if (it.origen != AnimeFuente.AUTO || (it.fuente to it.slug) in bad) continue
            val byEvidence = withEvidence && it.estado == AnimeFuente.APLICADO && it.nivel == "probable"
            val byFormat = withFormat && it.estado == AnimeFuente.SIN_RESULTADO && it.nivel != "descartado" && it.nivel != "mezcla" && AnilistMatcher.searchPlan(slugTitle(it.slug)) != null
            if (byEvidence) weak++ else if (byFormat) format++ else continue
            bad[it.fuente to it.slug] = it
        }
        return Redo(bad.values.toList(), weak, format)
    }

    /** Todos los nombres de la ficha guardada (con sinónimos), leídos de su JSON; null si no se puede leer. */
    private fun candidateOf(ficha: AnimeFicha): AnilistMatcher.Candidate? = FichaData.ofAnilist(ficha)?.let { data ->
        AnilistMatcher.Candidate(ficha.anilistId, ficha.formato, ficha.tituloRomaji, ficha.tituloIngles, ficha.tituloNativo, data.sinonimos, ficha.anio)
    }

    /**
     * Si el enlace de [slug] tiene sentido: null si no hay títulos con los que juzgar (ficha sin descargar o ilegible). Primero con los
     * títulos ya normalizados en [titles] (barato) y, solo si no pasan, con todos los nombres de la ficha ([ficha]).
     */
    fun consistent(slug: String, titles: AnimeFichaDao.Titles?, ficha: () -> AnimeFicha? = { null }): Boolean? {
        if (titles == null || (titles.tituloRomaji.isNullOrBlank() && titles.tituloIngles.isNullOrBlank())) return null
        val quick = AnilistMatcher.Candidate(titles.anilistId, null, titles.tituloRomaji, titles.tituloIngles, null)
        // Con un año en el slug el título guardado no basta (no lleva año): se juzga con la ficha completa.
        if (AnilistMatcher.split(slugTitle(slug)).year == null && fits(slug, quick)) return true
        val full = ficha()?.let(::candidateOf) ?: return null
        return fits(slug, full)
    }

    /**
     * Si la ficha de la web y el anime de AniList comparten el id de MyAnimeList ([web] = el de la ficha web): es la prueba más fuerte que hay y manda sobre el parecido de títulos
     * (`romelia-senki` frente a «Romelia Senki: Maou wo Taoshita Nochi mo Jinrui Yabasou dakara Guntai Soshiki shita»: título corto contra uno larguísimo, mismo `idMal`).
     */
    internal fun sameMal(web: Long?, ficha: AnimeFicha?): Boolean = web != null && ficha?.malId == web

    /** Las fichas (fuente, slug) cuyo enlace automático no se parece a su anime, según los nombres guardados en `CatalogDB`: no unen series. */
    fun doubtful(rows: Collection<AnimeFuente>): Set<Pair<String, String>> {
        val fichas = app.kaizen.database.CatalogDB.INSTANCE.animeFichaDao()
        val web = app.kaizen.database.CatalogDB.INSTANCE.webFichaDao()
        val start = System.nanoTime()
        var loads = 0
        val doubtful = inconsistent(rows, fichas.titles().associateBy { it.anilistId }, { f, s -> web.get(f, s)?.malId }, { id -> loads++; fichas.get(id) }).map { it.fuente to it.slug }.toSet()
        app.kaizen.commons.DebugLog.info("Medición", "Enlaces dudosos: ${rows.size} filas, ${doubtful.size} dudosos, $loads fichas leídas enteras, ${(System.nanoTime() - start) / 1_000_000} ms")
        return doubtful
    }

    /**
     * Las filas automáticas que no valen: las aplicadas cuyo anime no se parece a su ficha (según los nombres guardados) y las aplicadas o por revisar cuya ficha es una
     * película, un OVA o un especial enlazada a una serie de TV. El emparejado las rehace; las del usuario no se tocan.
     */
    fun inconsistent(rows: Collection<AnimeFuente>, titles: Map<Long, AnimeFichaDao.Titles>, webMal: (String, String) -> Long? = { _, _ -> null }, ficha: (Long) -> AnimeFicha? = { null }): List<AnimeFuente> {
        if (verdicts.size > MAX_VERDICTS) verdicts.clear()
        return rows.filter { isInconsistent(it, titles, webMal, ficha) }
    }

    /** Todo lo que decide el veredicto de una fila sin mirar la ficha completa ni el MAL de la web: la fila, y los títulos guardados de su anime. */
    private data class VerdictKey(val slug: String, val anilistId: Long, val estado: String, val nivel: String, val titles: AnimeFichaDao.Titles?)

    /**
     * Los veredictos ya calculados: comprobar los ~28.000 enlaces costaba unos 23 s de CPU cada vez (se repetía varias veces al día con el mismo resultado), y casi todos se deciden
     * solo con el slug y los títulos guardados. Solo se guardan esos; los que necesitan la ficha completa o el MAL de la web (pocos) se rehacen siempre, porque eso puede cambiar sin que cambie la clave.
     */
    private val verdicts = java.util.concurrent.ConcurrentHashMap<VerdictKey, Boolean>()
    private const val MAX_VERDICTS = 100_000

    private fun decided(key: VerdictKey, value: Boolean): Boolean { verdicts[key] = value; return value }

    /** Si el enlace automático [row] no vale (ver [inconsistent]): una regla por línea, de la más barata a la más cara. */
    private fun isInconsistent(row: AnimeFuente, titles: Map<Long, AnimeFichaDao.Titles>, webMal: (String, String) -> Long?, ficha: (Long) -> AnimeFicha?): Boolean {
        val id = row.anilistId ?: return false
        if (row.origen != AnimeFuente.AUTO || (row.estado != AnimeFuente.APLICADO && row.estado != AnimeFuente.PENDIENTE)) return false
        val key = VerdictKey(row.slug, id, row.estado, row.nivel, titles[id])
        verdicts[key]?.let { return it }
        // Película, OVA o especial enlazada a una serie de TV: se decide por el slug y los títulos.
        if (formatClashes(row.slug, key.titles)) return decided(key, true)
        // Solo los aplicados por nombre se comprueban con el título: los de MAL y los de la web ya tienen una prueba mejor.
        if (row.estado != AnimeFuente.APLICADO || row.nivel == AnimeFuente.NIVEL_MAL || row.nivel == AnimeFuente.NIVEL_WEB) return decided(key, false)
        var usedFicha = false
        val full by lazy { ficha(id) }
        val consistent = consistent(row.slug, key.titles) { usedFicha = true; full }
        // Con los títulos guardados bastó (o no hay con qué juzgar): el veredicto no depende de nada más y se guarda.
        if (!usedFicha) return decided(key, false)
        // Con la ficha completa: no vale si no se parece y la web no comparte el MAL (la ficha se lee una vez).
        return consistent == false && !sameMal(webMal(row.fuente, row.slug), full)
    }
}

/**
 * Qué series unidas merecen mirarse: las que juntan fichas con títulos que no se parecen (una mezcla de dos series). Así se revisan unas
 * decenas, ordenadas de más a menos sospechosa, en vez de miles.
 */
internal object JoinedCheck {
    /** Por debajo de este parecido entre las fichas de una serie, la serie es sospechosa. */
    const val MIN_COHESION = 0.4

    /**
     * El parecido de la serie consigo misma: de cada ficha, lo que más se parece a otra, y de esas la que menos (una ficha ajena baja todo).
     * Si todas las fichas están enlazadas al mismo anime de AniList ([linked]) cuentan como de acuerdo (1,0).
     */
    fun cohesion(fichas: List<Pair<String, String>>, linked: Map<Pair<String, String>, Long>): Double {
        if (fichas.size < 2) return 1.0
        val ids = fichas.map { linked[it] }
        if (ids.all { it != null } && ids.toSet().size == 1) return 1.0
        val words = fichas.map { (_, slug) -> AnilistMatcher.split(AnilistLinkCheck.slugTitle(slug)).words.toSet() }
        if (words.any { it.isEmpty() }) return 1.0
        // El mismo parecido que el emparejado: palabras compartidas o letras pegadas («hackgu-trilogy», «hack-gu-trilogy» y «hackgutrilogy» son el mismo título).
        return words.indices.minOf { i -> words.indices.filter { it != i }.maxOf { j -> AnilistMatcher.wordSimilarity(words[i], words[j]) } }
    }
}
