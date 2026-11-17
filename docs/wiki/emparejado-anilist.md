# Emparejado con AniList: cómo se decide, qué se aprendió y qué queda

Una explicación única, escrita el 4-nov-2026, de cómo se enlaza cada ficha de las webs con su anime de AniList y por qué. Si difiere del código, manda el código (las pruebas
`AnilistMatcherTest`, `AnilistEvidenciaTest`, `AnilistFormatoTest`, `FichaDataTest`, `FichaResueltaTest` y `FichaFillTest` fijan lo esencial). El esquema de las fichas está en
[webficha-esquema.md](webficha-esquema.md); el mapa del código en [CODEBASE-MAP](../CODEBASE-MAP.md).

## 1. Decisiones que mandan

- **Una unión de series ocurre solo si dos fichas tienen el mismo anime de AniList** (o la hizo el usuario). AniList es la base: no se une entre fuentes por parecido.
- **Un enlace automático no se aplica solo por el nombre**: la ficha tiene que tener las mismas características que el anime (año, formato, episodios, idMal).
- **`WebFicha` se mantiene limpia**: es lo que dijo la web, la prueba con la que se empareja y se revisa. Nunca se rellena con datos de AniList. Lo que se ve sale de
  `FichaResuelta` (AniList primero y, campo a campo, la web para lo que falte).
- **Un código para cada cosa**: si existe, se reutiliza y, si no encaja exacto, se adapta. Antes de escribir una comparación nueva se busca la que hay.
- **Las cadenas de TV no son estudios** (verani las llama «Estudio»): van a `producers`.
- **El id del tráiler de YouTube es una prueba floja**: solo a favor, nunca resta ni decide.
- Cada lote nuevo pasa `ship.js check`, una auditoría independiente y la autorización explícita del usuario para publicar.

## 2. El flujo, de la web al enlace

1. **Catálogo** (`CatalogBuilder`): recorre cada fuente y guarda solo nombre, nota, votos y año de cada ficha. Rehacerlo (acción ADB `WALK`) **no** añade episodios, formato ni
   `idMal`: esos solo salen de abrir la ficha de la web.
2. **`WebFicha`** (catalog-db v3): al abrir una ficha se guarda su registro JSON único (todas las claves, la forma de `Media` de AniList). Se llena de tres formas con el mismo código
   (`FichaFill`): al abrir la ficha, al comparar una propuesta en Configuración (`Catalog.facts`) y, para las que no están enlazadas, la acción ADB `FILL_FICHAS` o la casilla
   «Completar fichas de las webs en segundo plano» (apagada de fábrica; 20 cada 10 min, 300 al día, sin reproducir y con red; un fallo se anota una semana).
3. **Emparejado** (`AnilistSync`, en segundo plano): por ficha busca candidatos en AniList (`AnilistClient.search`), decide el nivel (`AnilistMatcher.decide`) y escribe el enlace
   (`AnimeFuente`). Antes mira el `idMal` de la web (`byMal`).
4. **Uniones**: `CatalogBuilder.regroup` junta las fichas enlazadas al mismo anime. Quitar un enlace deshace su unión.
5. **Revisión** (Configuración › Propuestas con AniList): las dudosas se comparan campo a campo (`AnilistCompare`) y se aceptan solas solo con pruebas fuertes (`auto`).
6. **Limpieza** (al arrancar, `cleanInconsistent`): quita los enlaces automáticos que ya no valen y el emparejado los rehace; nunca toca los del usuario ni sus descartes.

## 3. Los criterios

### Nivel del emparejado
El nombre da la puntuación (palabras base o letras pegadas, con retoques por temporada, formato y año). Sobre ella:

| Nivel | Condición de nombre | Se aplica |
| --- | --- | --- |
| Seguro | puntuación ≥ 1 y las otras búsquedas de la serie traen el mismo anime | sí, si ninguna prueba lo contradice |
| Probable | ≥ 0,85 | solo con **al menos una prueba a favor** |
| Dudoso | ≥ 0,1 | no: propuesta para el usuario |

### Pruebas (`AnilistMatcher.evidenceOf`, con las comparaciones de `AnilistCompare`)
Suman si coinciden y restan si no: el año (el de la serie o el de la web, con un año de margen), el `idMal` y los episodios (solo en series terminadas). **El formato solo resta**:
casi todo es «TV» y que coincida no prueba nada. Una prueba en contra baja SEGURO y PROBABLE a dudoso.

### Formato
- Una **película, OVA o especial** (el slug dice `movie`, `ova`, `especiales`…) no se enlaza con una serie de TV: son entradas distintas en AniList (`formatClash`; no cuenta si el propio
  anime lleva la palabra, p. ej. «Special A»; el ONA cuenta como serie).
- Se **busca con su formato** (`searchPlan`): el texto sin la palabra de formato y `format_in` (movie → MOVIE; ova → OVA, ONA, SPECIAL; especiales → SPECIAL, OVA). Sin el filtro la búsqueda
  devuelve la serie y la entrada propia ni aparece. Si con el filtro no sale nada parecido se repite el título entero sin filtro (series con la palabra en el nombre).
- Su nombre es **ambiguo** (varios comparten el título base): ni siendo igual basta, necesita una prueba más.

### Temporada y año
«x 2nd season» no es «X»: otra temporada no se propone. Un año del slug que no cuadra baja el nivel. El año de una ficha de AniList es el de la fecha de inicio y, si falta, el de la temporada.

## 4. Una sola pieza por cosa (unificado el 4-nov)

| Qué | Dónde |
| --- | --- |
| Leer el registro de una ficha | `FichaData` (web y AniList; `valor(campo)` por nombre de getter o ruta del JSON) |
| Qué dato se ve (AniList o web) | `FichaResuelta` |
| Primer paso del normalizado | `normalizedBase` (`normalizeTitle` y `AnilistMatcher.normalize`) |
| Plegado de vocales largas | `foldVowels` |
| Comparar años | `LinkEvidence.yearsClose(margen)`; `SeriesMatch.yearsFit` = margen 0 |
| Parecido de palabras | `AnilistMatcher.wordSimilarity` (emparejado, comprobación de enlaces y `JoinedCheck`) |
| Comparar formato, idMal, año, episodios | `AnilistCompare` (revisión y emparejado) |
| Abrir fichas de las webs | `FichaFill` (acción ADB y trabajo en segundo plano) |

## 5. Herramientas (ver `tools/README.md`)

`tv.js ficha "NOMBRE" CAMPO [--resuelta]` (consulta por ADB), `tv.js fill-fichas`, `tv.js status`, `tv.js awake`, `audit/audit.py pending`, `audit/format_search.py`, `ficha-preview.sh`.

## 6. Lo que se midió (exportaciones de la TV del 4-nov)

- 28.035 fichas; 22.406 enlaces «seguro», 801 «probable» aplicados solo por el nombre, 1.755 por revisar, 2.763 sin resultado.
- 749 animes tenían 2 o más fichas de la misma fuente; **356** eran una ficha de serie con otra de película/OVA/especial. El criterio de formato quitó **286** enlaces automáticos.
- De los 801 «probable» aplicados solo por el nombre, el criterio de formato ya había quitado unos 220; con el criterio por pruebas se volvieron a mirar los **583** restantes.
- Buscar con el formato encuentra la entrada propia en el **31 %** de una muestra de 80 fichas; otro 31 % no tiene entrada de ese formato en AniList (deben seguir sin enlace).
- «Revisar» en series unidas: 11 antes, **28** con el parecido unificado (casi todo películas y OVAs unidos a su serie).
- La TV tenía 4 `WebFicha`; tras el relleno de muestra (60) y las propuestas abiertas, más de 120, con `idMal` en el 50 %.

## 7. Lo aprendido

- **Dos sistemas con criterios distintos**: la revisión ya decidía por pruebas y el automático por nombre. «Probable» aplicado solo por el nombre fue la causa de unir una película de un capítulo con
  una serie de veintidós.
- **Medir antes de cambiar**: el catálogo no tiene los datos que hacían falta (episodios, formato, idMal); rehacerlo no los añade. Cada cambio de criterio se midió con las exportaciones
  (`audit/*.py`) y lo afectado se contó antes.
- **Corregir lo nuevo no corrige lo guardado**: un cambio de criterio solo alcanza a lo que se vuelva a mirar. Hace falta una migración dirigida (`AnilistLinkCheck.redo`: una vez, con marca al
  final) en vez de subir `AnilistMatcher.VERSION` y reemparejar todo.
- **Cada auditoría independiente encontró algo real** (bucles por la ruta de `idMal`, tope que no se aplicaba, carrera al reiniciar el emparejado, el formato que relajaba la aceptación,
  una migración que no llegaba a ejecutarse): ninguna se habría visto con las pruebas solas. Las pruebas de una función extraída (`redo`, `allowance`, `plan`) valen más que las de la cadena entera.
- **Los tres sitios por donde entra un enlace** (nombre, `idMal`, revisión) tienen que aplicar la misma regla; una regla puesta en uno solo se rodea por los otros.
- **Los datos de las webs no son iguales**: jkanime sin acentos, animeflv «Aventuras», verani con cadenas de TV como estudio, animegratis y animeav1 con bloqueos 403/404. Hay que normalizar y no fiarse de un campo.
- **El registro puede ir por detrás**: antes de afirmar que algo no ocurrió hay que leerlo hasta el final (una pasada de emparejado ocurrió después de mi lectura).

## 7b. Las uniones viajan entre equipos (18-nov)

Los enlaces y las fichas de AniList son por equipo (`user-db` y `catalog-db`) y no salen en la copia de estados: una tele nueva repetía horas de emparejado (la TV iba por 32 fichas frente a 7.004 del tvbox). `AnilistShare` los pasa por la carpeta de la sincronización: enlaces aplicados (nunca pisan uno existente ni del usuario; rellenan fichas sin enlace o con uno automático no aplicado) y fichas de AniList en tramos de 5000 ids con el recuento en el nombre. Con enlaces nuevos se reagrupa el catálogo. El catálogo de `kaizen-sources` ya traía los `anilistId` como enlaces «mismo-id», pero no las fichas. Límite: dos equipos con el mismo número de fichas distintas en un tramo no se intercambian hasta que uno crezca. Pruebas: `AnilistShareTest`.

## 8. Pendiente

Resumen y detalle de los límites conocidos (abajo). Lo que sigue abierto se lleva aparte, fuera del repositorio.

1. **Comprobar en la TV** (no cubierto por pruebas): las filas «Formato» y «MyAnimeList» de una propuesta, la pestaña «Datos» y la cabecera de una ficha de AniList, y cuántas de las 583 «probable» y de las «sin resultado» de formato se reaplican.
2. **Decisiones del usuario**: encender o no el relleno en segundo plano; si el catálogo agrupa con año ±1 (hoy exacto, `SeriesMatch`); qué hacer con las «especiales» agrupadas en una sola ficha que AniList reparte en varias entradas.
3. **Criterio**: películas sin marca en el slug (comparar capítulos de la ficha con los episodios de AniList); el formato como prueba; los «seguro» ya aplicados con una prueba en contra no se reabren; la ruta `idMal` no aplica la regla de episodios.
4. **Unificación que queda**: `normalizeTitle` y `AnilistMatcher.normalize` son dos salidas del mismo primer paso; `Catalog.SourceFacts` repite lo de `FichaData`; los scripts de `tools/audit` tienen copias en Python del criterio.
5. **Robustez**: pruebas de `applyFill`, tope de peticiones por arranque en la migración, tráfico a AniList en la repetición sin filtro, `cleanQuery` quita partículas y ordinales.
6. **Repositorio**: `CLAUDE.md`, `THIRD_PARTY.md`, `.vscode/settings.json` siguen sin confirmar y fuera de los commits; copias en `backups/`; relanzar `sync_docs.js` tras publicar.
7. **Permisos**: `ship.js publish` lo bloquea el clasificador sin autorización explícita en cada lote (la regla de permitir por prefijo no se pudo añadir).

### Límites conocidos y mejoras opcionales (detalle, de las auditorías del 4-nov)

- **`WebFichaQuery.find` por sinónimos** (auditoría del 4-nov, menor): sin coincidencia por título recorre las fichas por tramos de 200 y descomprime cada JSON; hoy solo la llama la acción ADB `FICHA_GET` (`node tools/tv.js ficha`, desde el PC, fuera del hilo principal). Si se usa desde la interfaz, llamarla fuera del hilo principal o guardar los sinónimos en una columna. Los `%` y `_` del texto de búsqueda actúan como comodín en el título nativo (solo amplía resultados).
- **Mejoras opcionales del emparejado por `idMal`** (auditoría del 4-nov, menores): (a) si un `idMal` correcto se rechaza porque el slug está en otro idioma y los nombres de la web no se parecen a los de AniList, la ficha queda «sin resultado»; mejor escribirla como «por revisar» con el candidato y nivel `mal`. (b) `WebFicha.descargada` se reescribe en cada apertura de ficha y repite `byMal` y las búsquedas por título de esa ficha; solo cuesta peticiones. (c) Separar `AnimeFuente.nivel` en confianza y procedencia exige migración de `user-db`.
- **Menores abiertos de la auditoría del 4-nov (protocolo):** `kept` reescribe la fila sin condición SQL (ventana de microsegundos); sin pruebas del receptor ADB, del cableado de `hints` ni de la migración real (se comprueban en la TV); `viaMal` cuenta aunque no se escriba.
- **Dos años para un mismo anime de AniList** (auditoría del 4-nov, menor): `FichaData.anio` (lo que se ve en la ficha y compara `AnilistCompare.year`) es el de la fecha de inicio y luego el de la temporada; `AnilistClient` (candidatos del matcher y columna `AnimeFicha.anio`) sigue con temporada y luego inicio. Solo difieren en animes que empiezan a finales de año. No se unifica aquí porque cambiaría lo que ve el matcher (subir `AnilistMatcher.VERSION` y medir con `audit.py pending`).
- **Películas sin marca en el slug y número de capítulos** (4-nov): el criterio de formato solo ve `movie/ova/especial` en el slug. Una película cuyo slug no lo dice (1.550 enlaces automáticos van a un anime MOVIE de AniList sin marca, casi todos bien) se podría separar comparando los capítulos de la ficha de la fuente con los episodios de AniList (1 frente a 22). Falta tener ese número por ficha en el emparejado.
- **Criterio de formato: límites conocidos** (auditoría del 4-nov, menores): (a) `decide` mira el mejor candidato; si es la serie y la película existe con menos puntuación, el resultado es «ninguno», no la película; (b) la limpieza de enlaces usa solo los nombres romaji e inglés guardados (`Titles`), `decide` también el nativo y los sinónimos: una palabra de formato solo en un sinónimo podría repetir quitar/rehacer; (c) un slug de una temporada real con «especial» que el anime no lleve en ningún nombre se rechazaría (no medido). Siguiente lote: niveles por evidencia y una sola regla compartida con Series.
- **Relleno en segundo plano: límites conocidos** (auditoría del 4-nov): una página que da éxito sin dejar `WebFicha` también cuenta como fallo (una semana fuera). Faltan pruebas de `applyFill` (necesita DAOs falsos).
- **Unificar los criterios** (4-nov): hecho el normalizado base, el plegado de vocales, la función de años, el parecido de palabras (matcher y `JoinedCheck`) y el emparejado por pruebas. **Queda**: `SeriesMatch.yearsFit` exige año igual y `LinkEvidence` ±1 (el catálogo agrupa con margen 0: decidir si pasa a ±1 midiendo cuántos grupos cambian), `normalizeTitle` y `AnilistMatcher.normalize` siguen siendo dos salidas del mismo primer paso (`foldTitle` y `AnilistMatcher.fold` igual, con orden distinto), `Catalog.SourceFacts` repite lo que ya da `FichaData`, `AnilistClient` pone la temporada primero, `AnilistCompare.auto` (por evidencia) y `AnilistMatcher.decide` (por nombre) deciden con criterios distintos y los scripts de `tools/audit` tienen copias en Python del criterio. Hacerlo midiendo antes cuántos grupos de series cambian.
- **Límites del criterio por pruebas** (auditoría del 4-nov): solo se reinician los enlaces automáticos «probable»; los «seguro» ya aplicados con una prueba en contra no se reabren (fijado en `AnilistFormatoTest`); la ruta por `idMal` de la web (`matchBatch`) no aplica la regla de episodios; mientras el emparejado rehace los ~800 «probable» (o sin red) esas fichas están sin enlace y sus uniones deshechas. `wordSimilarity` cuesta unas 3 o 4 veces más que antes por llamada (sin medir).
- **Búsqueda por formato: límites conocidos** (auditoría del 4-nov): `cleanQuery` quita también partículas y ordinales del texto buscado («x ii movie» → «x»); un grupo «x» + «x-ova» solo se beneficia de la búsqueda filtrada si el primer título lleva la marca; OVA/especial frente a ONA resta (clase «corto» frente a «serie»); `format_search.py` no replica `cleanQuery` ni mide cuántas se aplicarían solas (con prueba más).
- **Enlaces de partida (`AnilistSeed`), menores de la auditoría del 5-nov:** el fichero solo declara `matcherVersion` pero la siembra marca también `anilistEvidence` y `anilistFormatSearch` al día (añadir `evidenceVersion` y `formatVersion` al fichero y a `rows()`); `runCatching` en `startSync` traga la `CancellationException`; `make-links-seed.py` valida menos que la app (slug 1 a 300, id > 0, regex de fuente), no cierra el fichero que abre y no verifica la firma al final; no hay protección contra reponer un fichero antiguo con el mismo `matcherVersion` (bajo: solo actúa con la tabla vacía).
