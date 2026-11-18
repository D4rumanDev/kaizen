// Traduce la sinopsis de una serie con https://animeoteca.com/api/translate.php, que según lo visto pide {"text": "..."}.
// Paso 1: pide la sinopsis (inglés, la de AniList) a /api/anilist.php. Paso 2: se la manda a /api/translate.php. Dos peticiones en total.
//
// Uso: abre https://animeoteca.com, F12 > Consola, pega TODO este fichero y pulsa Intro. Cambia ID por el id de AniList (1535 = Death Note).
// translate.php puede pedir más campos (idioma de destino...): este script NO adivina ninguno. Si la respuesta dice que falta algo, el mensaje te lo dirá;
// pásamelo y lo añadimos con el nombre real. Aquí solo se manda "text".

(async () => {
  const ID = 1535;

  const post = async (path, body) => {
    const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body) });
    const raw = await res.text();
    let json = null;
    try { json = JSON.parse(raw); } catch { /* no es JSON */ }
    return { status: res.status, type: res.headers.get('content-type'), raw, json };
  };

  // --- Paso 1: la sinopsis original, en texto plano (sin <br>, <i> ni la línea «(Source: ...)»)
  const query = `query ($id: Int) { Media(id: $id, type: ANIME) { id title { romaji } description(asHtml: false) } }`;
  const a = await post('/api/anilist.php', { query, variables: { id: ID } });
  const media = (a.json && (a.json.data ?? a.json).Media) || null;
  if (!media || !media.description) {
    console.error('Paso 1 sin sinopsis:', a);
    return;
  }
  const original = media.description
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/\(Source:[^)]*\)/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  console.log(`%cOriginal (${original.length} caracteres):`, 'font-weight:bold');
  console.log(original);

  // --- Paso 2: la traducción
  const t = await post('/api/translate.php', { text: original });
  console.log(`%ctranslate.php → HTTP ${t.status} · ${t.type} · claves: ${t.json ? Object.keys(t.json).join(', ') : '(no es JSON)'}`, 'font-weight:bold');
  if (t.json) {
    if (t.json.error) console.warn('error:', t.json.error);
    console.log('respuesta completa:', t.json);
  } else {
    console.log('respuesta (no JSON):', t.raw.slice(0, 800));
  }

  // Lo más probable es que el texto traducido venga en alguna de estas claves; se enseña la primera que exista.
  const found = t.json && ['translation', 'translated', 'text', 'result', 'data', 'traduccion'].find((k) => typeof t.json[k] === 'string');
  if (found) console.log(`%cTraducción (clave «${found}», ${t.json[found].length} caracteres):`, 'font-weight:bold', '\n\n' + t.json[found]);
})();
