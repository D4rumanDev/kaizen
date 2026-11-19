---
title: WebFicha: esquema de la ficha
type: reference
tags:
  - kaizen
  - webficha
  - esquema
updated: 2026-11-19
---

# WebFicha: el registro único de cada ficha

Lo que dice cada web de una serie se guarda **siempre igual**, sea cual sea la fuente: un JSON con la estructura de `Media` de AniList, con **todas las
claves siempre** (lo que la web no da va como `null` o `[]`). Así se puede comparar con AniList campo a campo y unir series sin que cada fuente tenga su
forma. El esquema vive en **un solo sitio del código**, [WebFichaSchema.kt](../../app/src/main/java/app/kaizen/source/WebFichaSchema.kt); de ahí salen las
claves que puede declarar una fuente, el JSON vacío, cómo se lee cada valor, las enumeraciones válidas y las columnas indexadas. Este documento es la
explicación; si difiere del código, manda el código (y `WebFichaEsquemaTest` lo comprueba).

## El JSON

```json
{
  "idMal": null,                                  // entero: id de MyAnimeList (el mismo que idMal de AniList)
  "title": { "romaji": "…", "english": null, "native": null },
  "synonyms": [],                                 // otros nombres; nunca repiten los tres de arriba
  "format": null,                                 // TV | TV_SHORT | MOVIE | SPECIAL | OVA | ONA | MUSIC
  "status": null,                                 // FINISHED | RELEASING | NOT_YET_RELEASED | CANCELLED | HIATUS
  "episodes": null,                               // entero
  "duration": null,                               // minutos por episodio
  "season": null,                                 // WINTER | SPRING | SUMMER | FALL
  "seasonYear": null,
  "startDate": { "year": null, "month": null, "day": null },
  "endDate":   { "year": null, "month": null, "day": null },
  "countryOfOrigin": null,                        // JP, KR, CN, TW…
  "source": null,                                 // MANGA | LIGHT_NOVEL | ORIGINAL | VIDEO_GAME | …
  "genres": [],                                   // nombres, como los da la web
  "studios": [],                                  // estudios de animación
  "producers": [],                                // productoras y cadenas
  "characters": [],                               // { name, native, role: MAIN|SUPPORTING|BACKGROUND, voiceActors: [] }
  "staff": [],                                    // { name, role }
  "description": { "es": null, "en": null },      // la sinopsis en el idioma en que la da la web
  "isAdult": null,                                // true | false
  "coverImage": { "extraLarge": null },           // URL https de la carátula (la de tamaño completo, sin parámetros de redimensionado, cuando la web la da así)
  "bannerImage": null,                            // URL
  "trailer": { "id": null, "site": null },        // id de YouTube
  "web": {                                        // lo que no es de AniList
    "fuente": "…", "slug": "…", "url": "…",       // de dónde sale
    "score": null,                                // nota de la web SOBRE 5 (la de las estrellas de la app), en todas las fuentes
    "votes": null,
    "quality": null,                              // «1080p»
    "ageRating": null,                            // «PG-13»
    "audio": []                                   // audios que anuncia la web
  }
}
```

**Reglas**

- **Todas las claves siempre.** Las mismas claves y los mismos tipos en las siete fuentes y en cualquier fuente futura. Sin dato: `null` (valor suelto), `[]` (lista) o
  sus partes a `null` (fechas, `title`, `description`).
- **Título obligatorio.** `title.romaji` es el título principal de la web. Sin él la página no es una ficha.
- **No se inventa.** Una enumeración solo toma valores de AniList (y las listas de esos valores viven únicamente en el esquema: nada las copia): si el código de la web no está en la tabla `map` de la fuente, queda `null`. La
  comparación del código ignora mayúsculas y acentos («En emisión» = «en emision»).
- **Alternativas.** Con una lista de selectores vale la primera que da un valor **interpretable**: una que da texto pero no una fecha o un código conocido no tapa a la siguiente.
- **Fechas** como `{ year, month, day }`; si la web solo da el año, el mes y el día quedan `null`. Se entienden `2026-07-08`, «3 de octubre de 2017» y
  «Viernes, 16 de Junio de 2023».
- **La nota va sobre 5** (`web.score`), aunque la web la dé sobre 10; la fuente declara su máximo (`scoreMax`) y la app normaliza con un solo código.
- **`genres`** los lee `anime.genreLinks` (con su slug, para navegar); el registro guarda los nombres.
- Fuera del esquema, por ahora: `relations`, `tags`, `nextAiringEpisode`, `averageScore` (es la nota de AniList: la de la web va en `web.score`).
- Algunos datos son un juicio sobre lo que la web quiere decir. Ejemplo: verani llama «Estudio» a TV Tokyo, TV Aichi…; son cadenas, así que van a `producers`.

## Columnas indexadas (derivadas del JSON, solo para buscar)

El JSON es la fuente de verdad. La tabla `WebFicha` de `catalog-db` lleva además, para buscar sin descomprimirlo:

| Columna | De dónde sale |
| --- | --- |
| `malId` (con índice) | `idMal` |
| `nombres` | todos los nombres (`title`, `synonyms`) normalizados, más el japonés tal cual, entre barras: `\|nombre\|otro\|` |
| `anio` | `startDate.year`, o `seasonYear` si falta |
| `inicio` | la fecha de inicio como número `AAAAMMDD` (0 en lo que falte) |
| `formato`, `estado`, `temporada`, `episodios`, `adulto` | `format`, `status`, `season`, `episodes`, `isAdult` |

No se indexan nota, votos, estudios, géneros, personajes ni sinopsis; se leen del JSON. Si hace falta indexar otro campo, se añade una columna en una línea
de `FichaData` (y a `WebFicha.of`) y una migración.

## Cómo declara una fuente su parte

Cada `assets/sources/<fuente>.json` lleva un bloque `ficha` con **solo claves del esquema** (una desconocida, o la falta de `ficha` o de `romaji`,
rechaza la fuente al leerla). Cada clave es un selector, igual que en el resto del fichero (CSS en las páginas HTML, ruta en las de datos JSON):

```json
"ficha": {
  "romaji": { "sel": "h1" },
  "status": { "sel": "dt:matchesOwn(^Estado$) + dd", "map": { "Finalizado": "FINISHED", "En emisión": "RELEASING" } },
  "studios": { "sel": "dt:matchesOwn(^Estudio$) + dd a" },
  "producers": { "sel": "dt:matchesOwn(^Productoras$) + dd", "split": "\\s+·\\s+" },
  "characters": { "item": "article", "name": { "sel": "h3 a" }, "role": { "sel": "span", "map": { "Principal": "MAIN" } }, "voiceActors": { "sel": "a[href*=/seiyuu/]" } },
  "description": { "sel": "#sinopsis" }, "descriptionLanguage": "es",
  "score": { "regex": "(\\d+[.,]\\d)\\s*/\\s*10" }, "scoreMax": 10
}
```

- Un valor puede ser un selector, una lista de alternativas (vale el primero que da resultado) o un objeto con `sel`, `attr`, `regex`, `all`…
- `map`: código de la web → valor de AniList (solo en enumeraciones; todos los valores se comprueban al leer la fuente).
- `split`: parte un texto que trae varios valores. Las listas (`synonyms`, `studios`, `producers`, `audio`) reúnen todos los elementos que casan.
- `characters` y `staff` son listas de objetos: `item` es el selector de cada elemento y el resto sus campos.
- `descriptionLanguage` (`es` o `en`) dice en qué idioma está la sinopsis; con `description` es obligatorio. `scoreMax` (solo 5 o 10) es obligatorio con `score`.
- El bloque `anime` **ya no repite** nada de esto: guarda solo lo que no es dato de la ficha (`require`, `id`, `day`, `genres…`, `relations`, `chapters`). La
  serie que usa la app (nombre, sinopsis, año, estudio, en emisión, nota, tipo) se **deriva** del registro: una sola lectura de la página.

## Añadir una fuente nueva

1. Escribir su `assets/sources/<id>.json` con `ficha` (rellenando todo lo que su página dé; el resto, sin declarar).
2. `node tools/sign-sources.sh` y subir su `version`.
3. Comprobarla con la web viva: `tools/ficha-preview.sh URL_DE_UNA_FICHA` (imprime el registro entero).
4. Añadir un fixture de su ficha en `app/src/test/fixtures/<id>/` y sumarla a `WebFichaEsquemaTest` y `WebFichaFuentesTest`: el primero exige la misma
   estructura, tipos y valores válidos que las demás; el segundo fija qué lee de ella.

No hay código por fuente: si una web da un dato que el esquema no tiene, se añade al esquema (y entonces lo reciben todas), no a esa fuente.

## Relacionado

- [emparejado-anilist.md](emparejado-anilist.md): cómo se empareja cada ficha con AniList.
- [architecture.md](architecture.md): dónde encaja `WebFicha` (§3 C).
- [CODEBASE-MAP.md](../CODEBASE-MAP.md): ficheros del esquema (`source/`).
