// Escucha lo que la propia web de Animeoteca pide a /api/ (translate.php, img.php, anilist.php...) para ver sus parámetros reales y qué devuelven.
// No inventa ninguna petición: solo registra las que hace la página.
//
// Uso: abre https://animeoteca.com, F12 > Consola, pega TODO este fichero y pulsa Intro. Después USA LA WEB sin recargar la página (que recarga borra el escucha):
// entra en una serie desde los enlaces de la propia web, cambia de pestaña o de ficha, abre una sinopsis. Cada llamada a /api/ saldrá en la consola.
// Para ver también lo que cargó ya la página antes de pegar esto, mira el listado «Ya cargado» que se imprime al pegarlo.
//
// Importante: NO registra cabeceras ni cookies, solo método, URL, cuerpo y el principio de la respuesta. Si algo del cuerpo es personal, quítalo antes de pegármelo.

(() => {
  const FILTER = '/api/';
  const MAX = 600; // caracteres de la respuesta que se enseñan
  const log = [];

  const short = (text) => (text == null ? '' : String(text).length > MAX ? String(text).slice(0, MAX) + `… (+${String(text).length - MAX})` : String(text));
  const note = (entry) => {
    log.push(entry);
    console.groupCollapsed(`%c${entry.method} ${entry.url}  →  ${entry.status}`, 'color:#7c3aed;font-weight:bold');
    if (entry.body) console.log('cuerpo enviado:', entry.body);
    console.log('tipo de respuesta:', entry.type);
    console.log('respuesta:', entry.response);
    console.groupEnd();
  };

  // --- fetch
  if (!window.__animeotecaFetch) {
    window.__animeotecaFetch = window.fetch;
    window.fetch = async function (input, init) {
      const url = typeof input === 'string' ? input : input.url;
      const res = await window.__animeotecaFetch.apply(this, arguments);
      if (url.includes(FILTER)) {
        const method = (init && init.method) || (typeof input !== 'string' && input.method) || 'GET';
        const body = init && init.body && typeof init.body === 'string' ? init.body : init && init.body ? `[${init.body.constructor.name}]` : '';
        res.clone().text().then((t) => note({ method, url, body: short(body), status: res.status, type: res.headers.get('content-type'), response: short(t) })).catch(() => {});
      }
      return res;
    };
  }

  // --- XMLHttpRequest
  if (!window.__animeotecaXhrOpen) {
    window.__animeotecaXhrOpen = XMLHttpRequest.prototype.open;
    window.__animeotecaXhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__m = method;
      this.__u = String(url);
      return window.__animeotecaXhrOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      if (this.__u && this.__u.includes(FILTER)) {
        this.addEventListener('loadend', () => {
          let response = '';
          try { response = this.responseType === '' || this.responseType === 'text' ? this.responseText : `[${this.responseType}]`; } catch { /* binario */ }
          note({ method: this.__m, url: this.__u, body: short(typeof body === 'string' ? body : body ? `[${body.constructor.name}]` : ''), status: this.status, type: this.getResponseHeader('content-type'), response: short(response) });
        });
      }
      return window.__animeotecaXhrSend.apply(this, arguments);
    };
  }

  // --- lo que la página ya cargó antes de pegar esto (solo URLs, sin cuerpos)
  const already = performance.getEntriesByType('resource').filter((e) => e.name.includes(FILTER)).map((e) => ({ url: e.name, tipo: e.initiatorType, ms: Math.round(e.duration) }));
  console.log('%cYa cargado desde /api/ (solo URLs):', 'font-weight:bold');
  console.table(already);

  // --- ayudas
  window.animeotecaLog = () => { console.table(log.map((e) => ({ metodo: e.method, url: e.url, estado: e.status, tipo: e.type }))); return log; };
  window.animeotecaStop = () => {
    if (window.__animeotecaFetch) { window.fetch = window.__animeotecaFetch; delete window.__animeotecaFetch; }
    if (window.__animeotecaXhrOpen) { XMLHttpRequest.prototype.open = window.__animeotecaXhrOpen; XMLHttpRequest.prototype.send = window.__animeotecaXhrSend; delete window.__animeotecaXhrOpen; delete window.__animeotecaXhrSend; }
    console.log('Escucha desactivado.');
  };

  // Para cuando ya conozcas la forma real de una petición (copiada de lo que ves arriba): UNA petición a mano, sin adivinar.
  //   await animeotecaProbe('/api/translate.php', { method: 'POST', body: { ...lo que viste... } })
  window.animeotecaProbe = async (path, { method = 'POST', body = null } = {}) => {
    if (!path.startsWith('/api/')) throw new Error('Solo rutas /api/ de esta web');
    const init = { method, headers: { Accept: 'application/json' } };
    if (body != null && method !== 'GET') { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
    const res = await window.fetch(path, init);
    const text = await res.text();
    console.log(`HTTP ${res.status} · ${res.headers.get('content-type')} · ${text.length} caracteres`);
    try { console.log(JSON.parse(text)); } catch { console.log(short(text)); }
    return text;
  };

  console.log('%cEscucha activo.%c Usa la web (sin recargar) y mira aquí. animeotecaLog() resume lo capturado; animeotecaStop() lo desactiva.', 'font-weight:bold;color:green', 'color:inherit');
})();
