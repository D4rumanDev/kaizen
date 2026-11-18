// Pide la sinopsis de una serie a https://animeoteca.com/api/anilist.php con la misma consulta GraphQL que usaría AniList.
//
// Uso: abre https://animeoteca.com en el navegador, F12 > Consola, pega TODO este fichero y pulsa Intro.
// (Ejecutado desde esa pestaña la petición es del mismo origen: no hay problemas de CORS.)
// Cambia ID por el id de AniList de la serie (1535 = Death Note, 5114 = Fullmetal Alchemist: Brotherhood, 15125 = Teekyuu).
// Hace UNA sola petición. No manda cookies propias ni datos tuyos más allá de lo que el navegador ya envía a esa web.

(async () => {
  const ID = 1535;
  const ENDPOINT = '/api/anilist.php';

  // Mismo formato que la documentación de AniList: `query` + `variables`. description(asHtml: false) da la sinopsis en texto plano.
  const query = `
    query ($id: Int) {
      Media(id: $id, type: ANIME) {
        id
        title { romaji english native }
        description(asHtml: false)
      }
    }`;

  let res;
  try {
    res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ query, variables: { id: ID } }),
    });
  } catch (e) {
    console.error('Sin respuesta (red o bloqueo del navegador):', e);
    return;
  }

  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    console.error(`HTTP ${res.status} (${res.headers.get('content-type')}), la respuesta no es JSON:`, text.slice(0, 500));
    return;
  }

  // AniList envuelve el resultado en `data`; anilist.php, según lo probado, devuelve `{ Media: ... }` directamente. Se aceptan las dos formas.
  const shape = json.data ? 'con envoltorio "data" (como AniList)' : 'sin envoltorio "data"';
  const media = (json.data ?? json).Media;

  console.log(`HTTP ${res.status} · ${res.headers.get('content-type')} · claves de la respuesta: ${Object.keys(json).join(', ')} · ${shape}`);
  if (json.errors) console.warn('errors:', json.errors);
  if (!media) {
    console.warn('No hay Media en la respuesta:', json);
    return;
  }

  const description = media.description ?? '';
  console.log('Título:', media.title);
  console.log(`Sinopsis (${description.length} caracteres${description.endsWith('…') ? ', CORTADA con …' : ''}):\n\n${description}`);
  console.log('Respuesta completa:', json);
})();
