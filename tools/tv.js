#!/usr/bin/env node
/**
 * El tvbox desde el PC: instalar y arrancar el APK de TV comprobando que vive y no se cae, ver el logcat de Kaizen y limpiarlo.
 *
 * Uso (desde la raíz del repo):
 *   node tools/tv.js deploy [--build] [--device IP:puerto] [--wait 10] [--apk ruta]
 *   node tools/tv.js logcat          (en vivo: Kaizen, CrashGuard, ErrorLog y errores de Android)
 *   node tools/tv.js clear-logcat
 *   node tools/tv.js logs [--out carpeta] [--lines 3000]   (baja el registro de la app y el logcat, y resume los fallos)
 *   node tools/tv.js awake on|off|status   (la TV no se apaga: para tareas largas como el emparejado con AniList)
 *   node tools/tv.js status                (recuentos de la app por ADB: favoritos, vistos, fichas de AniList y fichas de las webs; abre catalog-db, así que
 *                                           ejecuta su migración la primera vez; hay que activar «Control por ADB» en Configuración › Depuración)
 *   node tools/tv.js export-fichas         (copia las fichas de AniList a backups/fichas/fichas-anilist-<fecha>.jsonl.gz y comprueba el fichero en el PC)
 *   node tools/tv.js fill-fichas [--limit N] [--fuente id]  (abre las fichas de las webs por revisar con AniList y sin copia, a ritmo lento y repartidas entre fuentes; solo escribe WebFicha)
 *   node tools/tv.js ficha "NOMBRE" [RUTA] [--resuelta] (con --resuelta el dato sale de AniList si la ficha está enlazada y, si no lo tiene, de la web, y se indica de cuál; consulta lo guardado de las fichas de las webs por cualquier nombre y campo: web.score, startDate.year…; solo lectura)
 *   node tools/tv.js health [--no-logs]   (informe de salud, solo lectura: cuenta en el registro lo que vigilan los pendientes y lo compara con el anterior en backups/health/)
 *   node tools/tv.js backup [--confirm]    (BACKUP por ADB + bajada verificada a backups/tv-<fecha>; sin --confirm solo enseña el plan; escribe una copia en la TV)
 *   node tools/tv.js settings              (lista los ajustes que se pueden tocar por ADB, con su valor y sus opciones)
 *   node tools/tv.js setting NOMBRE [VALOR]  (lee o cambia uno; lo valida la app contra sus opciones; no toca «Control por ADB» ni el estado interno)
 *   node tools/tv.js sources [ID on|off | --check]  (lista las fuentes con su estado; activa o desactiva una; o mide las activas)
 *   node tools/tv.js anilist-auto [--start [--apply] [--limit N]]  (revisa sin pantalla las series dudosas con AniList: sin --apply solo cuenta cuántas se aceptarían, descartarían o preguntarían; con --apply --confirm guarda los enlaces claros, tras hacer la app una copia; sin --confirm la app solo dice qué haría)
 *   node tools/tv.js store  (los ficheros de preferencias de la app: clase, claves y tamaño, sin valores)
 *   node tools/tv.js calls [--day AAAA-MM-DD] [--urls]  (peticiones reales a cada web en un día, separando el uso del segundo plano; para saber si saturamos una fuente)
 *   node tools/tv.js warp [--evaluate]     (estado del túnel WARP solo para Kaizen; con --evaluate aplica ahora el modo elegido; el modo se cambia con «setting warp_modo»)
 *   node tools/tv.js errors | bypass       (resumen de errores de la sesión por número E01…; estado del bypass de Cloudflare)
 *   node tools/tv.js clear-cache | clear-log | reset-settings [--confirm]  (lo que hacen sus botones de Configuración; sin --confirm la app solo dice qué haría)
 *   node tools/tv.js laliga                (qué fuentes activas resuelven hoy a una IP bloqueada por un partido de LaLiga, según hayahora.futbol; solo lee)
 *   node tools/tv.js image-misses [--clear]  (cuántas imágenes inexistentes recuerda la app para no pedirlas otra vez; con --clear las olvida)
 *   node tools/tv.js open                (abre Kaizen en primer plano)
 *   node tools/tv.js series [--wait S] [--shot]  (abre directamente Configuración › Biblioteca › Por revisar › Dudas de AniList, que al abrirse consulta AniList y resuelve sola lo claro hasta la primera
 *                                           pregunta; con --shot guarda una captura en backups/capturas/ tras S segundos, 12 por defecto)
 *
 * «awake on» guarda en `backups/tv-awake-anterior.json` los valores de la TV (`screen_off_timeout`, `sleep_timeout` de Settings.Secure y
 * `stay_on_while_plugged_in`) y pone: pantalla siempre encendida mientras está enchufada (7), tiempo de apagado de pantalla al máximo y suspensión por
 * inactividad desactivada (-1). «awake off» los restituye tal como estaban. Si «on» se repite no pisa la copia de los valores originales. Cambia ajustes del
 * sistema del tvbox: úsalo mientras haga falta y vuelve a «off» después.
 *
 * «logs»: copia a `backups/tv-logs/<fecha>/` el registro propio de la app (`debug.jsonl`, JSON Lines, el que ve «Configuración › Depuración»; y su
 * versión en texto `debug.log`, con el formato de siempre, que es la que leen `health` y `retrace.js`) y el logcat del sistema, y resume en pantalla los
 * errores: cuántos hay por tipo, por número del gestor único (E01…, con de quién son y dónde pasan más) y las webs que fallan (código HTTP y dominio, sin parámetros de la URL). Los ficheros
 * guardados pueden llevar rutas y direcciones del usuario: la carpeta está ignorada por git y no se debe subir.
 *
 * «deploy»:
 *
 *  --build   compila antes (`gradlew --no-daemon :app:assembleTv`)
 *  --device  por defecto ADB_DEVICE del entorno o de tools/adb-device/.env (no se versiona)
 *  --wait    segundos que se deja correr la app antes de mirar el proceso y el logcat (10)
 *
 * Pasos de «deploy», y se para en el primero que falla (código de salida distinto de 0): adb connect · dispositivo en línea · (compilar) · adb install -r -f
 * (con -f: almacenamiento interno, si no un disco USB lento provoca ANR) · arrancar · proceso vivo · sin «FATAL EXCEPTION» ni «ANR in app.kaizen».
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const PACKAGE = 'app.kaizen';

const COMMAND = process.argv[2];

/**
 * La carpeta local de registros (ignorada por git): `backups/tv-logs`. La de antes se llamaba `backups/shield-logs`: si solo existe esa, se renombra; si existen las
 * dos (se renombró a mano a medias), se devuelven ambas y `health` lee la más reciente de cualquiera. Nunca se borra nada.
 */
function logsRoots() {
  const current = path.join(ROOT, 'backups', 'tv-logs');
  const old = path.join(ROOT, 'backups', 'shield-logs');
  if (fs.existsSync(old) && !fs.existsSync(current)) {
    try { fs.renameSync(old, current); console.log('[tv] backups/shield-logs renombrada a backups/tv-logs'); } catch (e) { console.log(`[tv] no se pudo renombrar backups/shield-logs (${e.code || e.message}): se leen las dos`); }
  }
  return [current, old].filter((d) => fs.existsSync(d));
}

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

function deviceFromEnvFile() {
  const file = path.join(ROOT, 'tools', 'adb-device', '.env');
  if (!fs.existsSync(file)) return null;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*ADB_DEVICE\s*=\s*(.+)$/);
    if (m) return m[1].trim();
  }
  return null;
}

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { cwd: ROOT, encoding: 'utf8', shell: process.platform === 'win32' && /gradlew/.test(cmd), ...opts });
  return { code: r.status, out: `${r.stdout || ''}${r.stderr || ''}`.trim() };
}

function fail(step, detail) {
  console.error(`FALLO en «${step}»${detail ? `: ${detail}` : ''}`);
  process.exit(1);
}

const device = arg('--device', process.env.ADB_DEVICE || deviceFromEnvFile());
if (!device) fail('dispositivo', 'define ADB_DEVICE en tools/adb-device/.env (ver .env.example) o pasa --device IP:puerto');
const apk = path.resolve(ROOT, arg('--apk', path.join('app', 'build', 'outputs', 'apk', 'tv', 'app-tv.apk')));
const wait = Number(arg('--wait', '10'));
const adb = (...a) => run('adb', ['-s', device, ...a]);

if (COMMAND === 'logcat' || COMMAND === 'clear-logcat') {
  run('adb', ['connect', device]);
  if (COMMAND === 'clear-logcat') { adb('logcat', '-c'); console.log('[tv] logcat limpio'); process.exit(0); }
  const live = spawnSync('adb', ['-s', device, 'logcat', '-s', 'Kaizen:V', 'CrashGuard:V', 'ErrorLog:V', 'AndroidRuntime:E'], { stdio: 'inherit' });
  process.exit(live.status || 0);
}
/**
 * El registro de la app es JSON Lines (`debug.jsonl`, una anotación por línea); aquí se pasa a texto con el formato de siempre
 * («fecha hora N/Área: mensaje» y debajo sus líneas de detalle) para que `health`, `retrace.js` y los resúmenes de abajo sigan leyéndolo igual.
 * Una línea cortada a medias (la app se cerró escribiéndola) se ignora; una de texto del formato antiguo pasa tal cual.
 */
function renderJsonl(text) {
  const out = [];
  for (const line of text.split('\n')) {
    if (!line.startsWith('{')) { if (line) out.push(line); continue; }
    try {
      const j = JSON.parse(line);
      out.push(`${j.t} ${j.l}/${j.a}: ${j.m}`);
      for (const d of j.d || []) out.push(d);
    } catch { /* línea incompleta */ }
  }
  return out.join('\n') + '\n';
}

/** Las anotaciones con número del gestor de errores (E01…), de un texto JSON Lines. */
function numberedErrors(jsonl) {
  const out = [];
  for (const line of jsonl.split('\n')) {
    if (!line.startsWith('{')) continue;
    try { const j = JSON.parse(line); if (j.code) out.push(j); } catch { /* línea incompleta */ }
  }
  return out;
}

if (COMMAND === 'logs') {
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
  logsRoots(); // antes de escribir: la carpeta antigua pasa a la nueva
  const out = path.resolve(ROOT, arg('--out', path.join('backups', 'tv-logs', stamp)));
  fs.mkdirSync(out, { recursive: true });
  // MSYS_NO_PATHCONV: Git Bash no debe convertir la ruta de /sdcard.
  const env = { ...process.env, MSYS_NO_PATHCONV: '1' };
  // Actual (JSON Lines) y, si queda, el de texto de versiones anteriores: el texto final es el antiguo seguido del nuevo.
  const remote = `/sdcard/Android/data/${PACKAGE}/files`;
  const pull = run('adb', ['-s', device, 'pull', `${remote}/debug.jsonl`, path.join(out, 'debug.jsonl')], { env });
  run('adb', ['-s', device, 'pull', `${remote}/debug.log`, path.join(out, 'debug.antiguo.log')], { env });
  const logcat = adb('logcat', '-d', '-t', arg('--lines', '3000'));
  fs.writeFileSync(path.join(out, 'logcat.txt'), logcat.out);
  const readIf = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : '');
  const jsonl = readIf(path.join(out, 'debug.jsonl'));
  const debugFile = path.join(out, 'debug.log');
  const app = readIf(path.join(out, 'debug.antiguo.log')) + renderJsonl(jsonl);
  if (app.trim()) fs.writeFileSync(debugFile, app);
  fs.rmSync(path.join(out, 'debug.antiguo.log'), { force: true });
  if (!app.trim()) console.log(`[tv] sin registro (${pull.out.split('\n').pop()})`);
  const all = [...app.split('\n'), ...logcat.out.split('\n')];
  // Un fallo = línea «E/…» del registro propio, «W KAIZEN: Error/Fallo» del logcat, o un crash; se agrupan por texto sin cifras.
  // Los del gestor único llevan su número delante («W KAIZEN: E06 No encontrado…»).
  const errors = all.filter((l) => /\sE\/|W KAIZEN\s*:\s*(Error|Fallo|E\d\d )|FATAL EXCEPTION|ANR in/.test(l));
  const kinds = new Map();
  for (const l of errors) {
    // Sin parámetros de URL (pueden llevar búsquedas o tokens) ni cifras: agrupa lo que es el mismo fallo.
    const k = l.replace(/^\S+\s+\S+\s+(\d+\s+\d+\s+)?/, '').replace(/(https?:\/\/[^\s?\]]+)\?[^\s\]]*/g, '$1?…').replace(/\d+/g, '#').slice(0, 110);
    kinds.set(k, (kinds.get(k) || 0) + 1);
  }
  // Errores con número del gestor único (E01…): cuántos hay de cada uno, de quién son y en qué web o recurso pasan más.
  const numbered = numberedErrors(jsonl);
  const byCode = new Map();
  for (const j of numbered) {
    const title = j.m.split(':')[0].replace(/\s*\([^)]*\)\s*$/, '');
    const entry = byCode.get(j.code) || { title, origin: j.origin || '?', n: 0, ctx: new Map() };
    entry.n += 1;
    if (j.ctx) entry.ctx.set(j.ctx, (entry.ctx.get(j.ctx) || 0) + 1);
    byCode.set(j.code, entry);
  }
  // Las webs que fallan: código HTTP y dominio, sin la ruta ni los parámetros de la URL.
  const hosts = new Map();
  for (const l of all) {
    const m = l.match(/Status=(\d{3}), URL=\[https?:\/\/([^/\]?]+)/);
    if (m) hosts.set(`${m[2]} → HTTP ${m[1]}`, (hosts.get(`${m[2]} → HTTP ${m[1]}`) || 0) + 1);
  }
  console.log(`[tv] guardado en ${path.relative(ROOT, out)} (debug.log ${app.length} B, logcat ${logcat.out.length} B)`);
  console.log(`[tv] ${errors.length} líneas de error`);
  [...kinds].sort((a, b) => b[1] - a[1]).slice(0, 12).forEach(([k, n]) => console.log(`  ${String(n).padStart(4)} × ${k}`));
  if (byCode.size) {
    console.log('[tv] errores por número (gestor único; de quién son y dónde pasan más):');
    [...byCode].sort((a, b) => b[1].n - a[1].n).forEach(([code, e]) => {
      const top = [...e.ctx].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([c, n]) => `${c} (${n})`).join(', ');
      console.log(`  ${String(e.n).padStart(4)} × ${code} ${e.title} · ${e.origin}${top ? ` · ${top}` : ''}`);
    });
  }
  if (hosts.size) {
    console.log('[tv] webs que fallan:');
    [...hosts].sort((a, b) => b[1] - a[1]).forEach(([k, n]) => console.log(`  ${String(n).padStart(4)} × ${k}`));
  }
  process.exit(0);
}
// «status» y «export-fichas» usan el receptor ADB de la app (Configuración › Depuración › Control por ADB, apagado de fábrica).
if (COMMAND === 'status' || COMMAND === 'export-fichas') {
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  const action = COMMAND === 'status' ? 'STATUS' : 'EXPORT_FICHAS';
  const r = adb('shell', 'am', 'broadcast', '-a', `app.kaizen.action.${action}`, '-n', `${PACKAGE}/.control.AdbControlReceiver`);
  const code = (r.out.match(/result=(\d+)/) || [])[1];
  const data = (r.out.match(/data="([^"]*)"/) || [])[1];
  console.log(`[tv] ${COMMAND}: ${data !== undefined ? data : r.out}`);
  if (code !== '1') fail(COMMAND, `la app respondió código ${code} (2 = «Control por ADB» desactivado; 3 = error)`);
  if (COMMAND === 'status') process.exit(0);

  // La exportación sigue en segundo plano: se espera a que aparezca el fichero final (el temporal acaba en .part) y a que deje de crecer.
  const name = (data.match(/→ (\S+)$/) || [])[1];
  if (!name) fail('export-fichas', 'la app no dio el nombre del fichero');
  const remote = `/sdcard/Android/data/${PACKAGE}/files/backups/${name}`;
  const sizeOf = () => Number((adb('shell', 'stat', '-c', '%s', remote).out.match(/^\d+$/) || [])[0] || 0);
  const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
  let last = -1;
  let stable = 0;
  for (let i = 0; i < 150 && stable < 2; i++) {
    sleep(2000);
    const now = sizeOf();
    stable = now > 0 && now === last ? stable + 1 : 0;
    last = now;
  }
  if (stable < 2) fail('export-fichas', `el fichero ${name} no apareció o no terminó en 5 minutos: mira «node tools/tv.js logs»`);
  const outDir = path.join(ROOT, 'backups', 'fichas');
  fs.mkdirSync(outDir, { recursive: true });
  const local = path.join(outDir, name);
  // MSYS_NO_PATHCONV: Git Bash no debe convertir la ruta de /sdcard.
  const pull = run('adb', ['-s', device, 'pull', remote, local], { env: { ...process.env, MSYS_NO_PATHCONV: '1' } });
  if (!fs.existsSync(local)) fail('adb pull', pull.out.split('\n').pop());
  // Comprobación en el PC, aparte de la de la app: cabecera, cada línea es JSON y la última dice cuántas fichas hay.
  const lines = require('zlib').gunzipSync(fs.readFileSync(local)).toString('utf8').split('\n').filter(Boolean);
  const head = JSON.parse(lines[0]);
  const tail = JSON.parse(lines[lines.length - 1]);
  const rows = lines.slice(1, -1).map((l) => JSON.parse(l));
  if (head.formato !== 'kaizen-fichas-anilist' || tail.fin !== rows.length || rows.some((x) => !x.anilistId || !x.json)) fail('comprobar la copia', 'el fichero no es una copia completa');
  console.log(`[tv] OK: ${rows.length} fichas de AniList en ${path.relative(ROOT, local)} (${(fs.statSync(local).size / 1048576).toFixed(1)} MB), comprobadas en el PC`);
  process.exit(0);
}
// «fill-fichas»: abre en la TV las fichas de las webs que están por revisar con AniList y aún no tienen copia (solo escribe WebFicha), repartidas entre las fuentes.
if (COMMAND === 'fill-fichas') {
  const limit = Number(arg('--limit', '20'));
  const source = arg('--fuente', '');
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) fail('fill-fichas', '--limit debe ser un entero de 1 a 200');
  if (source && !/^[a-z0-9]{1,30}$/.test(source)) fail('fill-fichas', `fuente no válida: ${source}`);
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  const r = adb('shell', 'am', 'broadcast', '-a', 'app.kaizen.action.FILL_FICHAS', '-n', `${PACKAGE}/.control.AdbControlReceiver`, '--ei', 'limit', String(limit), ...(source ? ['--es', 'fuente', `'${source}'`] : []));
  const code = (r.out.match(/result=(\d+)/) || [])[1];
  const data = (r.out.match(/data="([^"]*)"/) || [])[1];
  console.log(`[tv] fill-fichas: ${data !== undefined ? data : r.out}`);
  if (code !== '1') fail('fill-fichas', `la app respondió código ${code} (2 = «Control por ADB» desactivado; 3 = error)`);
  console.log('[tv] sigue en segundo plano (~1,5 s por ficha): mira «node tools/tv.js status» (webfichas_por_fuente) y, al acabar, «node tools/tv.js logs».');
  process.exit(0);
}
// «ficha NOMBRE [RUTA]»: consulta lo guardado de las fichas de las webs (WebFicha) con el mismo código que usa la app (WebFichaQuery).
if (COMMAND === 'ficha') {
  const name = process.argv[3];
  const resolved = process.argv.includes('--resuelta');
  const field = process.argv.slice(4).filter((a) => !a.startsWith('--'))[0] || '';
  if (!name || name.startsWith('--')) fail('ficha', 'uso: node tools/tv.js ficha "NOMBRE" [RUTA] [--resuelta]   (RUTA: el nombre de un dato —generos, anio, nota, alternativas…, los de FichaData.CAMPOS— o su ruta en el JSON: web.score, startDate.year, genres[0]; sin ruta, el registro entero)');
  if (!/^[A-Za-z0-9_.\[\]]{0,80}$/.test(field)) fail('ficha', `ruta no válida: ${field}`);
  if (resolved && !field) fail('ficha', '--resuelta necesita un campo: node tools/tv.js ficha "NOMBRE" CAMPO --resuelta');
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  // El nombre va en base64 (UTF-8): los nombres en japonés no pasan por la línea de órdenes, y las comillas simples evitan que el shell de la TV toque la ruta.
  const r = adb('shell', 'am', 'broadcast', '-a', 'app.kaizen.action.FICHA_GET', '-n', `${PACKAGE}/.control.AdbControlReceiver`,
    '--es', 'name64', `'${Buffer.from(name, 'utf8').toString('base64')}'`, '--es', 'path', `'${field}'`, ...(resolved ? ['--ez', 'resuelta', 'true'] : []));
  const code = (r.out.match(/result=(\d+)/) || [])[1];
  const data = (r.out.match(/data="([^"]*)"/) || [])[1];
  if (code !== '1') fail('ficha', data !== undefined ? data : `la app respondió código ${code} (2 = «Control por ADB» desactivado; 3 = error)`);
  const rows = JSON.parse(Buffer.from(data, 'base64').toString('utf8'));
  if (!rows.length) { console.log(`[tv] ficha: ninguna ficha guardada con «${name}» (la tabla se rellena al abrir las fichas en la app)`); process.exit(0); }
  rows.forEach((x) => console.log(`${x.fuente}/${x.slug}	${x.value === null ? '(sin dato)' : x.value}${resolved && x.origen ? `	[${x.origen}]` : ''}`));
  process.exit(0);
}
// «settings» / «setting NOMBRE [VALOR]» / «laliga» / «image-misses [--clear]»: ajustes y comprobaciones de la app por ADB (acciones SETTINGS, SETTING_GET, SETTING_SET, LALIGA, IMAGE_MISSES).
if (['settings', 'setting', 'laliga', 'image-misses', 'sources', 'errors', 'bypass', 'warp', 'anilist-auto', 'calls', 'store', 'clear-cache', 'clear-log', 'reset-settings'].includes(COMMAND)) {
  const NAME_OK = /^[a-z0-9_]{1,40}$/;
  const VALUE_OK = /^[A-Za-z0-9 ._()-]{0,40}$/;
  let action;
  let extras = [];
  if (COMMAND === 'settings') action = 'SETTINGS';
  else if (COMMAND === 'laliga') action = 'LALIGA';
  else if (COMMAND === 'errors') action = 'ERRORS';
  else if (COMMAND === 'bypass') action = 'BYPASS';
  else if (COMMAND === 'store') action = 'STORE';
  else if (COMMAND === 'calls') { action = 'CALLS'; const day = arg('--day', ''); if (day) { if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) fail('calls', '--day debe ser AAAA-MM-DD'); extras = ['--es', 'day', `'${day}'`]; } }
  else if (COMMAND === 'anilist-auto') {
    // anilist-auto -> cómo va; --start -> empieza (solo cuenta); --start --apply -> guarda de verdad; --limit N acota las series.
    action = 'ANILIST_AUTO';
    if (process.argv.includes('--start')) extras = ['--ez', 'start', 'true', ...(process.argv.includes('--apply') ? ['--ez', 'apply', 'true', ...(process.argv.includes('--confirm') ? ['--ez', 'confirm', 'true'] : [])] : []), '--ei', 'limit', String(Math.max(1, Math.floor(Number(arg('--limit', '5000'))) || 5000))];
  }
  else if (COMMAND === 'warp') { action = 'WARP'; if (process.argv.includes('--evaluate')) extras = ['--ez', 'evaluate', 'true']; }
  else if (COMMAND === 'sources') {
    // sources                -> lista; sources --check -> mide las activas; sources ID on|off -> activa o desactiva una.
    const id = process.argv[3];
    if (process.argv.includes('--check')) action = 'SOURCES_CHECK';
    else {
      action = 'SOURCES';
      if (id && !id.startsWith('--')) {
        if (!NAME_OK.test(id) || !['on', 'off'].includes(process.argv[4])) fail('sources', 'uso: node tools/tv.js sources [ID on|off | --check]');
        extras = ['--es', 'set', `'${id}'`, '--ez', 'on', process.argv[4] === 'on' ? 'true' : 'false'];
      }
    }
  }
  else if (['clear-cache', 'clear-log', 'reset-settings'].includes(COMMAND)) {
    // Destructivos (lo mismo que sus botones de Configuración): sin --confirm solo dice qué harían.
    action = { 'clear-cache': 'CACHE_CLEAR', 'clear-log': 'LOG_CLEAR', 'reset-settings': 'SETTINGS_RESET' }[COMMAND];
    if (process.argv.includes('--confirm')) extras = ['--ez', 'confirm', 'true'];
  }
  else if (COMMAND === 'image-misses') { action = 'IMAGE_MISSES'; if (process.argv.includes('--clear')) extras = ['--ez', 'clear', 'true']; }
  else {
    const name = process.argv[3];
    const value = process.argv[4];
    if (!name || !NAME_OK.test(name)) fail('setting', 'uso: node tools/tv.js setting NOMBRE [VALOR]   (sin VALOR lo lee; la lista con «node tools/tv.js settings»)');
    if (value !== undefined && !VALUE_OK.test(value)) fail('setting', `valor no válido: ${value}`);
    action = value === undefined ? 'SETTING_GET' : 'SETTING_SET';
    extras = ['--es', 'name', `'${name}'`, ...(value === undefined ? [] : ['--es', 'value', `'${value}'`])];
  }
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  const r = adb('shell', 'am', 'broadcast', '-a', `app.kaizen.action.${action}`, '-n', `${PACKAGE}/.control.AdbControlReceiver`, ...extras);
  const code = (r.out.match(/result=(\d+)/) || [])[1];
  const data = (r.out.match(/data="([^"]*)"/) || [])[1];
  if (code !== '1') fail(COMMAND, data !== undefined ? data : `la app respondió código ${code} (2 = «Control por ADB» desactivado en Configuración › Depuración; 3 = error)`);
  if (action === 'CALLS') {
    const r2 = JSON.parse(Buffer.from(data, 'base64').toString('utf8'));
    console.log(`[tv] peticiones reales a cada web el ${r2.dia} (uso = lo que hace la app al usarla; fondo = catálogo, relleno de fichas, emparejado; no cuenta caché, WebView, AniList, Animeoteca ni AniSkip)`);
    console.log('host'.padEnd(34) + 'uso'.padStart(8) + 'fondo'.padStart(8) + 'total'.padStart(8) + '  fuente');
    r2.hosts.forEach((h) => console.log(h.host.padEnd(34) + String(h.uso).padStart(8) + String(h.fondo).padStart(8) + String(h.uso + h.fondo).padStart(8) + (h.fuente ? '  sí' : '')));
    const src = r2.hosts.filter((h) => h.fuente);
    console.log(`[tv] solo webs de fuentes: uso ${src.reduce((a, h) => a + h.uso, 0)} · fondo ${src.reduce((a, h) => a + h.fondo, 0)}`);
    if (process.argv.includes('--urls')) { console.log('direcciones más repetidas (sin parámetros):'); r2.urls.forEach((u) => console.log(`${String(u.n).padStart(6)}  ${u.url}`)); }
  } else if (action === 'STORE') {
    // Una línea por fichero de preferencias: clase, nombre, claves y tamaño; «SIN REGISTRAR» es un fichero que KaizenStore no conoce.
    const rows = JSON.parse(Buffer.from(data, 'base64').toString('utf8'));
    rows.forEach((x) => console.log(`${x.clase.padEnd(14)} ${x.fichero.padEnd(34)} ${String(x.claves).padStart(5)} claves ${String(x.bytes).padStart(8)} B  ${x.que}`));
    const loose = rows.filter((x) => x.clase === 'SIN REGISTRAR').length;
    console.log(`[tv] ${rows.length} ficheros de preferencias${loose ? `, ${loose} SIN REGISTRAR (añadirlos a KaizenStore.entries)` : ', todos registrados'}`);
  } else if (action === 'SETTINGS') {
    JSON.parse(Buffer.from(data, 'base64').toString('utf8')).forEach((s) => console.log(`${s.nombre.padEnd(30)} ${String(s.valor).padEnd(10)} ${s.opciones.length ? s.opciones.join(' | ') : '(texto libre)'}`));
  } else console.log(`[tv] ${COMMAND}: ${data}`);
  process.exit(0);
}
if (COMMAND === 'backup') {
  // Copia nueva de la app en la TV (BACKUP) y bajada VERIFICADA de toda la carpeta de copias a backups/tv-<fecha> (carpeta nueva, nunca se sobrescribe).
  const dryRun = !process.argv.includes('--confirm');
  const remote = `/sdcard/Android/data/${PACKAGE}/files/backups/.`;
  if (dryRun) {
    console.log(`[tv] plan (no ejecutado, falta --confirm): 1) BACKUP por ADB · 2) adb pull de ${remote} a backups/tv-<fecha> (carpeta nueva) · 3) comprobar JSON y gzip. BACKUP también borra las copias caducadas, como «Crear copia».`);
    process.exit(0);
  }
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  const b = adb('shell', 'am', 'broadcast', '-a', 'app.kaizen.action.BACKUP', '-n', `${PACKAGE}/.control.AdbControlReceiver`);
  const made = b.out.match(/result=1, data="Copia creada: ([^ ]+\.json)/);
  if (!made) fail('BACKUP', b.out.split('\n').pop());
  console.log(`[tv] copia creada en la TV: ${made[1]}`);
  const iso = new Date().toISOString();
  let dest = path.join(ROOT, 'backups', `tv-${iso.slice(0, 10)}`);
  if (fs.existsSync(dest)) dest += `-${iso.slice(11, 16).replace(':', '')}`;
  fs.mkdirSync(dest, { recursive: true });
  const pull = run('adb', ['-s', device, 'pull', remote, dest], { env: { ...process.env, MSYS_NO_PATHCONV: '1' } });
  if (pull.code !== 0) fail('adb pull', pull.out.split('\n').pop());
  try {
    JSON.parse(fs.readFileSync(path.join(dest, made[1]), 'utf8'));
    require('zlib').gunzipSync(fs.readFileSync(path.join(dest, 'anilist-links.json.gz')));
  } catch (e) { fail('verificar la copia', e.message); }
  console.log(`[tv] bajada a ${path.relative(ROOT, dest)} (${pull.out.split('\n').pop()}); la copia es un JSON legible y anilist-links.json.gz descomprime (no se probó a restaurarla)`);
  console.log(`[tv] siguiente para las fichas por revisar: python tools/audit/audit.py pending ${path.relative(ROOT, path.join(dest, 'anilist-links.json.gz'))}`);
  process.exit(0);
}

if (COMMAND === 'health') {
  // Informe de salud (solo lectura): cuenta en el registro lo que vigilan los pendientes y lo compara con el informe anterior (backups/health/).
  const noLogs = process.argv.includes('--no-logs');
  let dir;
  if (noLogs) {
    // Las carpetas se llaman por su fecha: la más reciente de las dos raíces (la nueva y, si sigue, la antigua).
    const dirs = logsRoots().flatMap((root) => fs.readdirSync(root).filter((d) => fs.existsSync(path.join(root, d, 'debug.log'))).map((d) => ({ d, full: path.join(root, d) })))
      .sort((a, b) => (a.d < b.d ? -1 : a.d > b.d ? 1 : 0));
    dir = dirs.length ? dirs[dirs.length - 1].full : null;
  } else {
    const r = run('node', [__filename, 'logs', '--device', device]);
    const m = r.out.match(/guardado en (.+?) \(debug\.log/);
    dir = m && r.code === 0 ? path.resolve(ROOT, m[1]) : null;
    if (!dir) fail('tv.js logs', r.out.split('\n').slice(-3).join(' '));
  }
  if (!dir) fail('registros', 'no hay carpetas en backups/tv-logs');
  const debug = fs.readFileSync(path.join(dir, 'debug.log'), 'utf8');
  const lc = path.join(dir, 'logcat.txt');
  const all = `${debug}\n${fs.existsSync(lc) ? fs.readFileSync(lc, 'utf8') : ''}`;
  const patrones = {
    readNBytes: /readNBytes/g, 'HostGate (no se usará)': /no se usará durante/g, aplazadas: /aplazad/gi, 'raw.githubusercontent': /raw\.githubusercontent\.com/g,
    'animegratis no respondió': /animegratis\.net no respondió/g, 'animeav1 ocupado': /HostBusyException: animeav1/g, 'scope left the composition': /scope left the composition/g,
    'HTTP 403': /Status=403/g, 'HTTP 404': /Status=404/g, 'FATAL EXCEPTION': /FATAL EXCEPTION/g, 'ANR in app.kaizen': /ANR in app\.kaizen/g,
  };
  const cuentas = {};
  for (const [k, re] of Object.entries(patrones)) cuentas[k] = (all.match(re) || []).length;
  const st = adb('shell', 'am', 'broadcast', '-a', 'app.kaizen.action.STATUS', '-n', `${PACKAGE}/.control.AdbControlReceiver`);
  const status = {};
  for (const kv of ((st.out.match(/data="([^"]*)"/) || [])[1] || '').split(' ')) {
    const [k, v] = kv.split('=');
    if (k && /^\d+$/.test(v || '')) status[k] = Number(v);
  }
  const out = path.join(ROOT, 'backups', 'health');
  fs.mkdirSync(out, { recursive: true });
  const prevFile = fs.readdirSync(out).filter((f) => f.endsWith('.json')).sort().pop();
  const prev = prevFile ? JSON.parse(fs.readFileSync(path.join(out, prevFile), 'utf8')) : null;
  const delta = (a, b) => (b === undefined || b === a ? '' : ` (${a - b >= 0 ? '+' : ''}${a - b} desde ${prev.fecha.slice(0, 16)})`);
  const informe = { fecha: new Date().toISOString(), carpeta: path.relative(ROOT, dir), cuentas, status };
  console.log(`Informe de salud · ${informe.fecha.slice(0, 16)} · ${informe.carpeta}\n\nRegistro (apariciones):`);
  for (const [k, v] of Object.entries(cuentas)) console.log(`  ${String(v).padStart(4)}  ${k}${delta(v, prev ? prev.cuentas[k] : undefined)}`);
  console.log('\nEstado de la app:');
  if (Object.keys(status).length) for (const [k, v] of Object.entries(status)) console.log(`  ${String(v).padStart(5)}  ${k}${delta(v, prev ? prev.status[k] : undefined)}`);
  else console.log('  (sin datos: activa «Control por ADB» en Configuración › Depuración)');
  const lines = debug.split(/\r?\n/);
  const last = (re, n) => lines.filter((l) => re.test(l)).slice(-n).map((l) => `  ${l.slice(0, 200)}`);
  console.log(`\nÚltima pasada de AniList:\n${last(/AniList: Pasada/, 1).join('\n')}\n\nÚltimas mediciones de arranque («filas de la pantalla de inicio» no es un coste):\n${last(/I\/Arranque:/, 4).join('\n')}`);
  fs.writeFileSync(path.join(out, `${informe.fecha.replace(/[:.]/g, '-')}.json`), JSON.stringify(informe, null, 1));
  process.exit(0);
}

if (COMMAND === 'awake') {
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  const KEYS = [['system', 'screen_off_timeout'], ['secure', 'sleep_timeout'], ['global', 'stay_on_while_plugged_in']];
  const get = (ns, key) => adb('shell', 'settings', 'get', ns, key).out.trim();
  const saved = path.join(ROOT, 'backups', 'tv-awake-anterior.json');
  const mode = process.argv[3];
  const VALID = /^(-?\d{1,12}|null)$/; // un entero o «null» (ajuste sin definir)
  const show = () => KEYS.forEach(([ns, key]) => console.log(`[tv] ${ns} ${key} = ${get(ns, key)}`));
  if (mode === 'status') {
    show();
    console.log(fs.existsSync(saved) ? `[tv] valores originales guardados en ${path.relative(ROOT, saved)}` : '[tv] sin valores originales guardados (awake no está activado por esta herramienta)');
    console.log(`[tv] pantalla: ${adb('shell', 'dumpsys power').out.match(/mWakefulness=\w+/)?.[0] || 'desconocido'}`);
    process.exit(0);
  }
  if (mode === 'on') {
    if (!fs.existsSync(saved)) {
      // Solo se guarda lo que es un número o «null»: si adb falla, `get` devuelve un texto de error y no debe quedar como «valor original».
      const original = Object.fromEntries(KEYS.map(([ns, key]) => [`${ns}.${key}`, get(ns, key)]));
      const bad = Object.entries(original).filter(([, v]) => !VALID.test(v));
      if (bad.length) fail('awake on', `no se pudieron leer los valores originales (${bad.map(([k]) => k).join(', ')}); no se cambia nada`);
      fs.mkdirSync(path.dirname(saved), { recursive: true });
      fs.writeFileSync(saved, JSON.stringify(original, null, 2));
      console.log(`[tv] valores originales guardados en ${path.relative(ROOT, saved)} (si ya existe, no se pisa: son los de antes del primer «on»)`);
    }
    const wanted = [['global', 'stay_on_while_plugged_in', '7'], ['system', 'screen_off_timeout', '2147483647'], ['secure', 'sleep_timeout', '-1']];
    for (const [ns, key, value] of wanted) {
      if (adb('shell', 'settings', 'put', ns, key, value).code !== 0) fail('awake on', `no se pudo poner ${ns} ${key}`);
    }
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    show();
    console.log('[tv] la TV no se apagará. Para devolverla a como estaba: node tools/tv.js awake off');
    process.exit(0);
  }
  if (mode === 'off') {
    if (!fs.existsSync(saved)) fail('awake off', `no hay valores originales guardados (${path.relative(ROOT, saved)})`);
    const before = JSON.parse(fs.readFileSync(saved, 'utf8'));
    let failed = 0;
    for (const [ns, key] of KEYS) {
      const value = String(before[`${ns}.${key}`]);
      if (!VALID.test(value)) fail('awake off', `valor no válido en ${path.relative(ROOT, saved)} (${ns}.${key}); no se cambia nada más`);
      const r = value === 'null' ? adb('shell', 'settings', 'delete', ns, key) : adb('shell', 'settings', 'put', ns, key, value);
      if (r.code !== 0) { failed++; console.error(`[tv] no se pudo restituir ${ns} ${key}`); }
    }
    // El fichero con los originales solo se borra si todo se restituyó.
    if (failed) fail('awake off', `${failed} ajustes sin restituir; se conserva ${path.relative(ROOT, saved)}`);
    fs.unlinkSync(saved);
    show();
    console.log('[tv] ajustes de la TV restituidos');
    process.exit(0);
  }
  fail('uso', 'node tools/tv.js awake on | off | status');
}
if (COMMAND === 'open') {
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
  adb('shell', 'monkey', '-p', PACKAGE, '-c', 'android.intent.category.LEANBACK_LAUNCHER', '1');
  const top = adb('shell', 'dumpsys activity activities').out.match(/(?:topResumedActivity|mResumedActivity).*/)?.[0] || '';
  console.log(`[tv] en primer plano: ${top.includes(PACKAGE) ? 'Kaizen' : top.trim() || 'desconocido'}`);
  process.exit(top.includes(PACKAGE) ? 0 : 1);
}
if (COMMAND === 'series') {
  run('adb', ['connect', device]);
  if (adb('get-state').out !== 'device') fail('dispositivo en línea', 'no responde');
  adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
  // La sección 7 es Configuración (MainSections.TOOLS); `open` lo lee SettingsPage y abre el diálogo de Series.
  const started = adb('shell', 'am', 'start', '-n', `${PACKAGE}/.tv.ui.TVMain`, '--ei', 'section', '7', '--es', 'open', 'series', '--activity-clear-top');
  if (/Error|Exception/.test(started.out)) fail('abrir Series', started.out.split(/\r?\n/)[0]);
  console.log('[tv] Configuración › Biblioteca › Por revisar abierta (consulta AniList y resuelve sola lo claro; lo dudoso queda para el mando)');
  if (process.argv.includes('--shot')) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, Number(arg('--wait', '12')) * 1000);
    const shot = spawnSync('adb', ['-s', device, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 });
    const dir = path.join(ROOT, 'backups', 'capturas');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `series-${new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19)}.png`);
    fs.writeFileSync(file, shot.stdout);
    console.log(`[tv] captura: ${path.relative(ROOT, file)} (${shot.stdout.length} bytes)`);
  }
  process.exit(0);
}
if (COMMAND !== 'deploy') fail('uso', 'node tools/tv.js deploy | logcat | clear-logcat | logs | awake on|off|status | open | series | settings | setting | laliga | image-misses | sources | errors | bypass | warp | anilist-auto | calls | store | clear-cache | clear-log | reset-settings');

console.log(`[tv] dispositivo ${device}`);
let r = run('adb', ['connect', device]);
console.log(`[tv] adb connect: ${r.out.split('\n').pop()}`);
r = adb('get-state');
if (r.out !== 'device') fail('dispositivo en línea', r.out || 'no responde');

if (process.argv.includes('--build')) {
  console.log('[tv] compilando :app:assembleTv …');
  r = run(process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew', ['--no-daemon', ':app:assembleTv'], { stdio: ['ignore', 'pipe', 'pipe'] });
  if (r.code !== 0) fail('compilar', r.out.split('\n').slice(-15).join('\n'));
}
if (!fs.existsSync(apk)) fail('APK', `no existe ${apk} (usa --build)`);

console.log(`[tv] instalando ${path.relative(ROOT, apk)} (${(fs.statSync(apk).size / 1048576).toFixed(1)} MB) con -r -f …`);
r = adb('install', '-r', '-f', apk);
if (!/Success/.test(r.out)) fail('instalar', r.out);

adb('logcat', '-c');
r = adb('shell', 'monkey', '-p', PACKAGE, '-c', 'android.intent.category.LEANBACK_LAUNCHER', '1');
if (!/Events injected: 1/.test(r.out)) fail('arrancar', r.out.split('\n').slice(-3).join(' | '));
console.log(`[tv] app arrancada; espero ${wait} s …`);
spawnSync(process.execPath, ['-e', `setTimeout(()=>{}, ${wait * 1000})`]);

const pid = adb('shell', 'pidof', PACKAGE).out;
if (!/^\d+/.test(pid)) fail('proceso vivo', 'la app no está en ejecución tras arrancar');
const crashes = adb('shell', 'logcat', '-d').out.split('\n').filter((l) => /FATAL EXCEPTION|ANR in app\.kaizen/.test(l));
if (crashes.length) fail('crashes', crashes.slice(0, 3).join('\n'));
console.log(`[tv] OK: instalada y en ejecución (pid ${pid.split(/\s+/)[0]}), sin FATAL EXCEPTION ni ANR.`);
