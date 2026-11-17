#!/usr/bin/env node
/**
 * Publica un cambio con las comprobaciones de siempre, en dos pasos que obligan a auditar lo que se publica.
 *
 *   node tools/ship.js check [--add ruta ...] [--add-new] [--exclude ruta ...] [--skip-tests]
 *   node tools/ship.js docs -m "mensaje" RUTA [RUTA ...] [--no-sync] [--dry-run]   (solo documentación: finales de línea, datos sensibles, sync_docs.js y commit LOCAL de esas rutas; nunca push)
 *   node tools/ship.js push [--audited SHA]   (publica commits YA hechos en local, p. ej. los de `docs`: comprueba el rango origin/<rama>..HEAD y, con el SHA auditado, empuja y verifica con ls-remote)
 *   node tools/ship.js publish -m "mensaje" --audited SHA [--trailer "Co-Authored-By: …"] [--install]
 *
 * check    Reúne los ficheros a publicar (los cambios de git respecto a HEAD, más los nuevos que pases con --add, menos --exclude), los pasa
 *          por la búsqueda de datos que no deben subir (IPs de la red, usuario de Windows, secretos, ficheros de claves y `.env`), ejecuta
 *          `:app:testDebugUnitTest` y compila `:app:assembleTv`. Si todo pasa imprime el SHA del cambio exacto y lo guarda en build/ship-check.json.
 *          Ese SHA es lo que hay que dar a auditar.
 * publish  Solo si el cambio sigue siendo el mismo que se comprobó y se audita con el SHA (`--audited`): prepara exactamente esos ficheros, hace
 *          el commit, hace `git push` (nunca con --force) y verifica que la rama remota apunta al commit. Con --install también instala en la TV
 *          (`tools/tv.js deploy`). Antes de publicar exige que el `check` incluyera las pruebas (salvo `--allow-skipped-tests`) y que `origin/<rama>`
 *          sea ancestro de HEAD (si no, no es un avance simple y no se publica). Los nombres de fichero se toman literales (sin patrones).
 * No sustituye la auditoría: la exige. Lo que no está en la lista de `check` no se prepara, así que lo ajeno (ficheros de otra persona, arte sin
 * revisar) queda fuera de forma explícita en vez de entrar con un `git add -A`.
 */
const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const STATE = path.join(ROOT, 'build', 'ship-check.json');
const STATE_PUSH = path.join(ROOT, 'build', 'ship-push.json');

function sh(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 1 << 28, shell: process.platform === 'win32' && /gradlew/.test(cmd), ...opts });
  return { code: r.status, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}
// --literal-pathspecs: un nombre de fichero con *, ? o [ no se toma por un patrón.
const git = (...a) => sh('git', ['--literal-pathspecs', ...a]);
const list = (flag) => {
  const out = [];
  process.argv.forEach((v, i) => { if (v === flag && process.argv[i + 1]) out.push(process.argv[i + 1].replace(/\\/g, '/')); });
  return out;
};
const opt = (flag) => { const i = process.argv.indexOf(flag); return i >= 0 ? process.argv[i + 1] : null; };
const die = (msg) => { console.error(`ship: ${msg}`); process.exit(1); };

/** Ficheros del cambio: lo modificado, borrado o renombrado respecto a HEAD, y los nuevos pedidos con --add; sin lo excluido. */
function changeSet() {
  const tracked = git('diff', '--name-only', '--no-renames', 'HEAD').out.split('\n').filter(Boolean);
  // --add-new: todos los ficheros nuevos (no versionados y no ignorados), en vez de nombrarlos uno a uno con --add.
  const added = [...list('--add'), ...(process.argv.includes('--add-new') ? git('ls-files', '--others', '--exclude-standard').out.split('\n').filter(Boolean) : [])];
  for (const f of added) if (!fs.existsSync(path.join(ROOT, f)) && !tracked.includes(f)) die(`--add ${f}: no existe`);
  const exclude = list('--exclude');
  const all = [...new Set([...tracked, ...added])].map((f) => f.replace(/\\/g, '/'));
  return all.filter((f) => !exclude.some((x) => f === x || f.startsWith(x.endsWith('/') ? x : `${x}/`))).sort();
}

/** El SHA del cambio exacto: el diff de lo seguido (con binarios) y el contenido de lo nuevo. */
function changeSha(files) {
  const h = crypto.createHash('sha256');
  for (const f of files) {
    const known = git('ls-files', '--error-unmatch', '--', f).code === 0 || git('cat-file', '-e', `HEAD:${f}`).code === 0;
    h.update(`\n#${f}\n`);
    if (known) h.update(git('diff', '--binary', 'HEAD', '--', f).out);
    else h.update(fs.readFileSync(path.join(ROOT, f)));
  }
  return h.digest('hex');
}

// Datos que no deben subir. Las IPs de documentación (192.0.2.x), de bucle (127.x) y las de ejemplo de las pruebas están permitidas.
const BAD_NAMES = [/(^|\/)\.env$/, /(^|\/)local\.properties$/, /\.(jks|keystore|pass|p12|pem|pepk)$/, /(^|\/)google-services\.json$/];
const LINE_RULES = [
  [/\b(?:10\.(?!0\.0\.5\b)\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.(?!1\.2\b)\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.(?!0\.1\b)\d{1,3}\.\d{1,3})\b/, 'IP privada'],
  [new RegExp('jfr' + 'ico', 'i'), 'usuario de Windows'],
  [new RegExp(['ju', 'an\\.f\\.', 'ri', 'co'].join(''), 'i'), 'correo personal'],
  [/(?:password|passwd|secret|token|api[_-]?key)\s*[:=]\s*['"][^'"\s]{8,}['"]/i, 'posible secreto'],
  [/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/, 'clave privada'],
];

function scan(files) {
  const problems = [];
  for (const f of files) {
    if (BAD_NAMES.some((re) => re.test(f)) && !/\.example$/.test(f)) problems.push(`${f}: fichero que no se versiona`);
    const full = path.join(ROOT, f);
    if (!fs.existsSync(full) || /\.(png|webp|jpg|jpeg|apk|jar|ttf|gz)$/i.test(f)) continue;
    const tracked = git('cat-file', '-e', `HEAD:${f}`).code === 0;
    // De lo ya seguido solo se miran las líneas añadidas; de lo nuevo, todas.
    const lines = tracked
      ? git('diff', '-U0', 'HEAD', '--', f).out.split('\n').filter((l) => l.startsWith('+') && !l.startsWith('+++')).map((l) => l.slice(1))
      : fs.readFileSync(full, 'utf8').split(/\r?\n/);
    lines.forEach((line, i) => LINE_RULES.forEach(([re, what]) => { if (re.test(line)) problems.push(`${f}:${i + 1} ${what}`); }));
  }
  return problems;
}

const AJENOS = ['CLAUDE.md', 'THIRD_PARTY.md', 'docs/wiki/sdd/constitution.md'];

/** Los finales de línea (CRLF/LF) de estos ficheros no deben haber cambiado respecto a HEAD (`backslash.py eol`). */
function eolCheck(files) {
  const r = sh('python', [path.join('tools', 'backslash.py'), 'eol', 'check', ...files]);
  if (r.code !== 0) die(`cambió el final de línea (arréglalo con «python tools/backslash.py eol fix»):\n${r.out}`);
}

/** Cierre de documentación: comprueba, replica la documentación (si hay un replicador local) y hace commit LOCAL de las rutas indicadas. Nunca hace push. */
function docs() {
  const argv = process.argv.slice(3);
  const mi = argv.indexOf('-m');
  const message = mi >= 0 ? argv[mi + 1] : null;
  const files = argv.filter((a, i) => !a.startsWith('--') && a !== '-m' && i !== mi + 1).map((f) => f.replace(/\\/g, '/'));
  if (!message || !files.length) die('uso: node tools/ship.js docs -m "mensaje" RUTA [RUTA ...] [--no-sync] [--dry-run]');
  for (const f of files) {
    if (AJENOS.includes(f)) die(`${f} es un fichero ajeno: va fuera de los commits`);
    if (!git('diff', '--name-only', 'HEAD', '--', f).out) die(`${f} no tiene cambios respecto a HEAD`);
  }
  eolCheck(files);
  const problems = scan(files);
  if (problems.length) die(`datos que no deben subir:\n  ${problems.join('\n  ')}`);
  console.log(`[ship] docs: ${files.length} fichero(s) con los finales de línea intactos y sin datos sensibles`);
  if (process.argv.includes('--dry-run')) return console.log('[ship] --dry-run: no se replica ni se confirma');
  // El replicador es opcional y local (no se versiona): sin él, este paso se salta.
  const replicador = path.join('tools', 'basic-memory', 'sync_docs.js');
  if (!process.argv.includes('--no-sync') && fs.existsSync(path.join(ROOT, replicador))) {
    const s = sh('node', [replicador]);
    const resumen = s.out.split('\n').pop();
    if (s.code !== 0 || !/ 0 con error/.test(resumen)) die(`sync_docs.js: ${resumen}`);
    console.log(`[ship] sync_docs.js (replica todo el árbol de trabajo): ${resumen}`);
  }
  if (git('add', '--', ...files).code !== 0) die('git add falló');
  const c = git('commit', '-q', '-m', `${message}\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`, '--', ...files);
  if (c.code !== 0) die(`git commit falló: ${(c.out + c.err).split('\n').pop()}`);
  console.log(`[ship] commit local: ${git('log', '--oneline', '-1').out}\n[ship] ${git('status', '-sb').out.split('\n')[0]} (sin push)`);
}

function check() {
  const files = changeSet();
  if (!files.length) die('no hay cambios que publicar');
  console.log(`[ship] ${files.length} ficheros en el cambio:`);
  files.forEach((f) => console.log(`  ${f}`));
  eolCheck(files);
  const problems = scan(files);
  if (problems.length) die(`datos que no deben subir (nada se ha publicado):\n  ${problems.join('\n  ')}`);
  console.log('[ship] sin IPs, usuario, secretos ni ficheros de claves.');
  const gradle = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
  const tasks = process.argv.includes('--skip-tests') ? [':app:assembleTv'] : [':app:testDebugUnitTest', ':app:assembleTv'];
  console.log(`[ship] gradle ${tasks.join(' ')} …`);
  const r = sh(gradle, ['--no-daemon', ...tasks]);
  if (r.code !== 0) die(`gradle falló:\n${(r.out + '\n' + r.err).split('\n').slice(-25).join('\n')}`);
  // El mapping de R8 de esta compilación: sin él, las trazas del registro de la TV salen ilegibles. Se guarda en backups/mapping (ignorada por git).
  try { const id = require('./retrace.js').save(); if (id) console.log(`[ship] mapping de R8 ${id} guardado en backups/mapping`); } catch (e) { console.log(`[ship] no se pudo guardar el mapping: ${e.message}`); }
  const sha = changeSha(files);
  fs.mkdirSync(path.dirname(STATE), { recursive: true });
  fs.writeFileSync(STATE, JSON.stringify({ sha, files, at: new Date().toISOString(), tests: !process.argv.includes('--skip-tests') }, null, 2));
  console.log(`[ship] comprobado. SHA del cambio para auditar: ${sha}`);
}

function publish() {
  const message = opt('-m');
  const audited = opt('--audited');
  if (!message) die('falta -m "mensaje"');
  if (!audited) die('falta --audited SHA: audita el cambio que imprimió `check` y pasa su SHA');
  if (!fs.existsSync(STATE)) die('no hay una comprobación previa: ejecuta `check`');
  const state = JSON.parse(fs.readFileSync(STATE, 'utf8'));
  if (state.sha !== audited) die(`el SHA auditado (${audited.slice(0, 12)}…) no es el comprobado (${state.sha.slice(0, 12)}…)`);
  if (state.tests === false && !process.argv.includes('--allow-skipped-tests')) die('el `check` se hizo con --skip-tests: repítelo con las pruebas (o pasa --allow-skipped-tests y dilo en el informe)');
  const now = changeSha(state.files);
  if (now !== state.sha) die('el cambio ha variado desde `check`: vuelve a comprobarlo y a auditarlo');
  if (git('diff', '--cached', '--name-only').out.split('\n').filter(Boolean).some((f) => !state.files.includes(f))) {
    die('hay ficheros preparados (git add) que no están en el cambio comprobado: sácalos con `git restore --staged` o inclúyelos en check');
  }
  let r0;
  const branch = git('rev-parse', '--abbrev-ref', 'HEAD').out;
  if (branch === 'HEAD') die('HEAD suelto: no se publica fuera de una rama');
  // Lo local tiene que continuar lo publicado: si origin tiene commits que no están en HEAD, el push no sería un avance simple y nada se fuerza.
  r0 = git('fetch', 'origin', branch);
  if (r0.code !== 0) die(`git fetch origin ${branch}: ${r0.err || r0.out}`);
  if (git('merge-base', '--is-ancestor', 'FETCH_HEAD', 'HEAD').code !== 0) die(`origin/${branch} tiene commits que HEAD no contiene: integra o decide antes de publicar`);
  // Lo que existe se prepara con `add`; lo que ya no existe se prepara como borrado con `rm --cached` (`add` falla con un fichero borrado y ignorado).
  const present = state.files.filter((f) => fs.existsSync(path.join(ROOT, f)));
  const removed = state.files.filter((f) => !present.includes(f));
  let r = present.length ? git('add', '--', ...present) : { code: 0 };
  if (r.code !== 0) die(`git add: ${r.err}`);
  r = removed.length ? git('rm', '-q', '--cached', '--ignore-unmatch', '--', ...removed) : { code: 0 };
  if (r.code !== 0) die(`git rm: ${r.err}`);
  const trailer = opt('--trailer');
  r = git('commit', '-m', trailer ? `${message}\n\n${trailer}` : message);
  if (r.code !== 0) die(`git commit: ${r.err || r.out}`);
  const head = git('rev-parse', 'HEAD').out;
  console.log(`[ship] commit ${head.slice(0, 9)} en ${branch}`);
  r = git('push', 'origin', `HEAD:${branch}`);
  if (r.code !== 0) die(`git push falló (el commit local queda hecho; nada se fuerza):\n${r.err || r.out}`);
  const remote = git('ls-remote', 'origin', `refs/heads/${branch}`).out.split(/\s+/)[0];
  if (remote !== head) die(`el remoto apunta a ${remote.slice(0, 9)} y no a ${head.slice(0, 9)}`);
  console.log(`[ship] publicado: origin/${branch} = ${head.slice(0, 9)} (verificado con ls-remote).`);
  if (process.argv.includes('--install')) {
    const d = spawnSync(process.execPath, [path.join(__dirname, 'tv.js'), 'deploy'], { cwd: ROOT, stdio: 'inherit' });
    if (d.status !== 0) die('la instalación falló (lo publicado queda como está)');
  }
}

/**
 * Publica commits que YA están hechos en local (los de `docs`, por ejemplo), en dos pasos como check/publish:
 *   push                  comprueba el rango origin/<rama>..HEAD (avance simple, datos sensibles, pruebas si toca app/ o Gradle), guarda el parche en backups/auditoria/ e imprime el SHA a auditar
 *   push --audited SHA    con ese SHA y sin cambios desde entonces, hace `git push` (nunca --force) y verifica la rama remota con ls-remote
 */
function pushCommitted() {
  const branch = git('rev-parse', '--abbrev-ref', 'HEAD').out;
  if (branch === 'HEAD') die('HEAD suelto: no se publica fuera de una rama');
  const f = git('fetch', 'origin', branch);
  if (f.code !== 0) die(`git fetch origin ${branch}: ${f.err || f.out}`);
  const base = git('rev-parse', 'FETCH_HEAD').out;
  if (git('merge-base', '--is-ancestor', base, 'HEAD').code !== 0) die(`origin/${branch} tiene commits que HEAD no contiene: integra o decide antes de publicar`);
  const head = git('rev-parse', 'HEAD').out;
  if (head === base) die('no hay commits locales por publicar');
  const files = git('diff', '--name-only', '--no-renames', `${base}..${head}`).out.split('\n').filter(Boolean);
  const patch = git('diff', '--binary', `${base}..${head}`).out;
  const sha = crypto.createHash('sha256').update(`${base}\n${head}\n${patch}`).digest('hex');
  const audited = opt('--audited');
  if (audited) {
    if (!fs.existsSync(STATE_PUSH)) die('no hay una comprobación previa: ejecuta `push` sin --audited');
    const st = JSON.parse(fs.readFileSync(STATE_PUSH, 'utf8'));
    if (st.sha !== audited || sha !== audited) die('el SHA auditado no coincide con el rango actual: repite la comprobación y la auditoría');
    if (st.tests === false) die('el rango toca app/ o Gradle y la comprobación no pasó las pruebas');
    const p = git('push', 'origin', `${head}:refs/heads/${branch}`);
    if (p.code !== 0) die(`git push falló (nada se fuerza):\n${p.err || p.out}`);
    const remote = git('ls-remote', 'origin', `refs/heads/${branch}`).out.split(/\s+/)[0];
    if (remote !== head) die(`el remoto apunta a ${remote.slice(0, 9)} y no a ${head.slice(0, 9)}`);
    return console.log(`[ship] publicado: origin/${branch} = ${head.slice(0, 9)} (verificado con ls-remote), ${files.length} ficheros.`);
  }
  console.log(`[ship] ${git('rev-list', '--count', `${base}..${head}`).out} commit(s) y ${files.length} ficheros por publicar:`);
  files.forEach((x) => console.log(`  ${x}`));
  const problems = files.filter((x) => BAD_NAMES.some((re) => re.test(x)) && !/\.example$/.test(x)).map((x) => `${x}: fichero que no se versiona`);
  patch.split('\n').filter((l) => l.startsWith('+') && !l.startsWith('+++')).forEach((l) => LINE_RULES.forEach(([re, what]) => { if (re.test(l)) problems.push(`${what}: ${l.slice(0, 100)}`); }));
  eolCheckRange(base, head);
  if (problems.length) die(`datos que no deben subir (nada se ha publicado):\n  ${problems.join('\n  ')}`);
  console.log('[ship] sin IPs, usuario, secretos ni ficheros de claves en lo añadido.');
  const needsTests = files.some((x) => x.startsWith('app/') || /(^|\/)(build\.gradle|settings\.gradle|gradle\.properties)$/.test(x));
  if (needsTests) {
    const gradle = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
    console.log('[ship] el rango toca app/ o Gradle: gradle :app:testDebugUnitTest :app:assembleTv …');
    const g = sh(gradle, ['--no-daemon', ':app:testDebugUnitTest', ':app:assembleTv']);
    if (g.code !== 0) die(`gradle falló:\n${(g.out + '\n' + g.err).split('\n').slice(-25).join('\n')}`);
  } else console.log('[ship] el rango no toca app/ ni Gradle: no se ejecutan pruebas ni compilación.');
  fs.mkdirSync(path.dirname(STATE_PUSH), { recursive: true });
  fs.writeFileSync(STATE_PUSH, JSON.stringify({ sha, base, head, files, tests: true, at: new Date().toISOString() }, null, 2));
  const patchFile = path.join(ROOT, 'backups', 'auditoria', `push-${head.slice(0, 9)}.patch`);
  fs.mkdirSync(path.dirname(patchFile), { recursive: true });
  fs.writeFileSync(patchFile, patch);
  console.log(`[ship] parche para auditar: ${path.relative(ROOT, patchFile)}\n[ship] SHA del rango para auditar: ${sha}`);
}

/** Como eolCheck, pero sobre un rango ya confirmado: ningún fichero cambia de final de línea entre la base y HEAD. */
function eolCheckRange(base, head) {
  const bad = [];
  for (const x of git('diff', '--name-only', '--no-renames', '--diff-filter=M', `${base}..${head}`).out.split('\n').filter(Boolean)) {
    const a = git('show', `${base}:${x}`), b = git('show', `${head}:${x}`);
    const kind = (s) => { const c = (s.match(/\r\n/g) || []).length, l = (s.match(/\n/g) || []).length - c; return c && l ? 'mixto' : c ? 'crlf' : l ? 'lf' : 'ninguno'; };
    const ka = kind(a.out), kb = kind(b.out);
    if (ka !== kb && ka !== 'ninguno' && kb !== 'ninguno') bad.push(`${x}: ${ka} -> ${kb}`);
  }
  if (bad.length) die(`cambió el final de línea entre la base y HEAD:\n  ${bad.join('\n  ')}`);
}

const cmd = process.argv[2];
if (cmd === 'check') check();
else if (cmd === 'publish') publish();
else if (cmd === 'docs') docs();
else if (cmd === 'push') pushCommitted();
else die('uso: node tools/ship.js check | publish  (ver la cabecera del fichero)');
