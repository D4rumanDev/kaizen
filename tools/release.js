#!/usr/bin/env node
/**
 * Automatiza el flujo completo de publicar: comprobar, auditar (con un auditor aparte), commit, push, release en GitHub e instalar en las teles. Reúne en dos órdenes lo que antes eran
 * seis pasos a mano, sin saltarse ninguna de las barreras de `tools/ship.js` (pruebas, escaneo de datos, SHA auditado, push sin force, verificación con ls-remote).
 *
 *   node tools/release.js prepare [--add ruta]... [--exclude ruta]... [--prev SHA_ANTERIOR]
 *   node tools/release.js ship --audited SHA -m "mensaje" --tag vX.Y.Z [--notes "novedades"] [--title t] [--devices IP:5555,IP:5555] [--no-release] [--no-install] [--trailer "Co-Authored-By: …"]
 *
 * prepare  Ejecuta `ship.js check` (pruebas, compilación, escaneo de IPs/secretos) y genera el diff para el auditor (`audit-diff.js`, con delta si pasas --prev). Imprime el SHA y la ruta del
 *          diff: eso es lo que hay que dar al auditor independiente. **La auditoría no se automatiza a propósito**: la hace otra instancia que lee el diff, y sin su «APROBADO» no se sigue.
 * ship     Con el SHA aprobado: `ship.js publish` (commit, push sin force, ls-remote), luego el release y, por último, la instalación:
 *            1. release: comprueba que el APK está firmado con el MISMO certificado que el del release (si no, se detiene y no sube nada), lo sube con `--clobber` a la etiqueta `--tag`
 *               (obligatoria; si la etiqueta NO existe se crea una release NUEVA en el commit publicado con `--notes`, comprobando el certificado contra el de la última), o sustituye en las notas el SHA-256 del APK por el nuevo y añade el commit, y verifica que el hash que GitHub guarda coincide con el del fichero local.
 *               La etiqueta NO se mueve (mover una etiqueta es reescribir una referencia publicada): las notas dicen de qué commit sale el APK.
 *            2. instalación: en cada equipo de `--devices` (o, sin ello, el de `tools/adb-device/.env`): `adb install -r -f`, abre la app, espera 10 s y comprueba proceso, FATAL y ANR.
 * Las IPs de las teles no se guardan en el repositorio: se pasan con `--devices`. Cada fallo detiene el proceso con el paso que falló y lo que ya quedó hecho.
 */
const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const APK = path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'tv', 'app-tv.apk');
const argv = process.argv.slice(2);
const cmd = argv[0];
const has = (flag) => argv.includes(flag);
const opt = (flag, fallback = null) => { const i = argv.indexOf(flag); return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback; };
const list = (flag) => argv.reduce((out, v, i) => (v === flag && argv[i + 1] ? [...out, argv[i + 1]] : out), []);

const step = (text) => console.log(`\n[release] ${text}`);
const die = (text, done = []) => {
  console.error(`\n[release] DETENIDO: ${text}`);
  if (done.length) console.error(`[release] Ya hecho antes del fallo: ${done.join('; ')}`);
  process.exit(1);
};
const run = (bin, args, options = {}) => spawnSync(bin, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options });
const node = (script, args, quiet = false) => {
  const r = run(process.execPath, [path.join(__dirname, script), ...args], { stdio: quiet ? ['ignore', 'pipe', 'pipe'] : ['ignore', 'pipe', 'inherit'] });
  if (!quiet && r.stdout) process.stdout.write(r.stdout);
  return r;
};

// ---------------------------------------------------------------------------------------------- prepare
function prepare() {
  step('ship.js check (pruebas, compilación y escaneo de datos)');
  const passthrough = argv.slice(1).filter((v, i, all) => v !== '--prev' && all[i - 1] !== '--prev');
  const check = node('ship.js', ['check', ...passthrough]);
  const sha = (check.stdout.match(/SHA del cambio para auditar: ([0-9a-f]{64})/) || [])[1];
  if (check.status !== 0 || !sha) die('`ship.js check` no pasó: arréglalo y repite (nada se ha publicado)');
  step('diff para el auditor');
  const prev = opt('--prev');
  const diff = node('audit-diff.js', [sha, ...(prev ? ['--prev', prev] : [])]);
  if (diff.status !== 0) die('no se pudo generar el diff para el auditor');
  const short = sha.slice(0, 8);
  console.log(`\n[release] LISTO PARA AUDITAR`);
  console.log(`  SHA:   ${sha}`);
  console.log(`  diff:  build\\audit-${short}.diff${prev ? `   delta: build\\audit-delta-${prev.slice(0, 8)}-${short}.txt` : ''}`);
  console.log(`  Dale el SHA y las rutas a un auditor independiente (auditor-bee). Con su «APROBADO»:`);
  console.log(`  node tools/release.js ship --audited ${sha} -m "mensaje" --devices IP:5555,IP:5555`);
}

// ---------------------------------------------------------------------------------------------- release
function apksigner() {
  const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), path.join(os.homedir(), 'Android', 'Sdk')].filter(Boolean);
  const exe = process.platform === 'win32' ? 'apksigner.bat' : 'apksigner';
  for (const root of roots) {
    const tools = path.join(root, 'build-tools');
    if (!fs.existsSync(tools)) continue;
    for (const version of fs.readdirSync(tools).sort().reverse()) {
      const candidate = path.join(tools, version, exe);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return null;
}

function apkCertificate() {
  const signer = apksigner();
  if (!signer) die('no encuentro `apksigner` (SDK de Android): no puedo comprobar la firma del APK, así que no lo subo');
  const r = run(signer, ['verify', '--print-certs', APK], { shell: process.platform === 'win32' });
  const cert = (r.stdout.match(/Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})/i) || [])[1];
  if (r.status !== 0 || !cert) die(`el APK no pasa \`apksigner verify\` (${(r.stderr || r.stdout || '').trim().split('\n')[0]})`);
  return cert.toLowerCase();
}

/** La etiqueta no existe: crea una release NUEVA (etiqueta en el commit publicado). El certificado debe ser el de la última release; las notas las pones tú con --notes. */
function createRelease(tag, commit, sha, cert, done) {
  const notesText = opt('--notes');
  if (!notesText) die(`la release ${tag} no existe y falta --notes "novedades" para crearla (nada se ha subido)`, done);
  const latest = run('gh', ['release', 'list', '--limit', '1', '--json', 'tagName', '-q', '.[0].tagName']).stdout.trim();
  const previous = latest ? JSON.parse(run('gh', ['release', 'view', latest, '--json', 'body']).stdout || '{"body":""}').body : '';
  const certPrevious = (previous.match(/Certificado \(SHA-256\): ([0-9a-f]{64})/i) || [])[1];
  if (!certPrevious) die(`las notas de la última release (${latest || 'ninguna'}) no traen el certificado: no puedo comprobar la firma, así que no se crea nada`, done);
  if (certPrevious.toLowerCase() !== cert) die(`el APK está firmado con otro certificado (${cert.slice(0, 12)}…) que el de ${latest} (${certPrevious.slice(0, 12)}…): no se crea nada`, done);
  const body = `${notesText} SHA-256 del APK: ${sha}. Certificado (SHA-256): ${cert}. Commit del APK: ${commit.slice(0, 9)}.`;
  const notes = path.join(os.tmpdir(), `kaizen-release-notes-${process.pid}.txt`);
  fs.writeFileSync(notes, body);
  step(`release NUEVA ${tag} sobre ${commit.slice(0, 9)} (${(fs.statSync(APK).size / 1048576).toFixed(1)} MB, sha256 ${sha.slice(0, 12)}…)`);
  const created = run('gh', ['release', 'create', tag, APK, '--target', commit, '--title', opt('--title', `Kaizen ${tag}`), '--notes-file', notes], { stdio: ['ignore', 'inherit', 'inherit'] });
  fs.rmSync(notes, { force: true });
  if (created.status !== 0) die('`gh release create` falló: comprueba con `gh release view` si quedó a medias antes de repetir', done);
  done.push(`release nueva ${tag} creada con el APK`);
  const after = JSON.parse(run('gh', ['release', 'view', tag, '--json', 'assets']).stdout || '{"assets":[]}');
  const asset = after.assets.find((a) => a.name === 'app-tv.apk');
  if (!asset || asset.digest !== `sha256:${sha}`) die(`GitHub guarda otro hash (${asset ? asset.digest : 'sin asset'}) que el local (sha256:${sha})`, done);
  done.push('hash verificado en GitHub');
}

function release(tag, commit, done) {
  if (!fs.existsSync(APK)) die(`no existe ${path.relative(ROOT, APK)}: compila antes (el paso prepare ya lo hace)`, done);
  const sha = crypto.createHash('sha256').update(fs.readFileSync(APK)).digest('hex');
  const cert = apkCertificate();
  const view = run('gh', ['release', 'view', tag, '--json', 'body,assets']);
  if (view.status !== 0 && /not found/i.test(view.stderr || '')) return createRelease(tag, commit, sha, cert, done);
  if (view.status !== 0) die(`no puedo leer el release ${tag} (¿gh sin sesión o etiqueta inexistente?): ${(view.stderr || '').trim()}`, done);
  const info = JSON.parse(view.stdout);
  const certInNotes = (info.body.match(/Certificado \(SHA-256\): ([0-9a-f]{64})/i) || [])[1];
  if (!certInNotes) die(`las notas del release ${tag} no traen «Certificado (SHA-256): …»: no puedo comprobar la firma, así que no se sube nada`, done);
  if (certInNotes.toLowerCase() !== cert) die(`el APK está firmado con otro certificado (${cert.slice(0, 12)}…) que el del release (${certInNotes.slice(0, 12)}…): no se sube nada`, done);
  step(`release ${tag}: subiendo app-tv.apk (${(fs.statSync(APK).size / 1048576).toFixed(1)} MB, sha256 ${sha.slice(0, 12)}…)`);
  const upload = run('gh', ['release', 'upload', tag, APK, '--clobber'], { stdio: ['ignore', 'inherit', 'inherit'] });
  if (upload.status !== 0) die('`gh release upload` falló (el asset del release puede haber desaparecido a medias: comprueba con `gh release view` y repite la subida)', done);
  done.push(`APK subido a ${tag}`);
  if (!/SHA-256 del APK: [0-9a-f]{64}/i.test(info.body)) console.warn('[release] ⚠ las notas no traen «SHA-256 del APK: …»: no se pudo actualizar ese dato (el hash del asset sí se verifica)');
  let body = info.body.replace(/SHA-256 del APK: [0-9a-f]{64}/i, `SHA-256 del APK: ${sha}`);
  body = body.replace(/\s*Commit del APK: [0-9a-f]{7,40}\.?/i, '').trimEnd() + ` Commit del APK: ${commit.slice(0, 9)}.`;
  const notes = path.join(os.tmpdir(), `kaizen-release-notes-${process.pid}.txt`);
  fs.writeFileSync(notes, body);
  const edit = run('gh', ['release', 'edit', tag, '--notes-file', notes], { stdio: ['ignore', 'inherit', 'inherit'] });
  fs.rmSync(notes, { force: true });
  if (edit.status !== 0) die('el APK se subió pero no se pudieron actualizar las notas del release (el SHA de las notas está desfasado)', done);
  done.push('notas del release actualizadas');
  const after = JSON.parse(run('gh', ['release', 'view', tag, '--json', 'assets']).stdout || '{"assets":[]}');
  const asset = after.assets.find((a) => a.name === 'app-tv.apk');
  if (!asset || asset.digest !== `sha256:${sha}`) die(`GitHub guarda otro hash (${asset ? asset.digest : 'sin asset'}) que el del fichero local (sha256:${sha}): vuelve a subirlo`, done);
  done.push('hash verificado en GitHub');
  console.log(`[release] ✓ ${tag}: hash verificado (${sha.slice(0, 16)}…), firmado con el certificado esperado`);
}

// ---------------------------------------------------------------------------------------------- instalación
function install(device, done) {
  const adb = (...args) => run('adb', ['-s', device, ...args]);
  run('adb', ['connect', device]);
  const result = adb('install', '-r', '-f', APK);
  if (!/Success/.test(result.stdout)) { console.error(`[release] ✗ ${device}: la instalación falló: ${(result.stdout + result.stderr).trim().split('\n').pop()}`); return false; }
  adb('shell', 'am', 'force-stop', 'app.kaizen');
  adb('shell', 'monkey', '-p', 'app.kaizen', '-c', 'android.intent.category.LEANBACK_LAUNCHER', '1');
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10000);
  const pid = (adb('shell', 'pidof', 'app.kaizen').stdout || '').trim();
  const crashes = Number((adb('shell', 'logcat -d | grep -cE "FATAL EXCEPTION|ANR in app.kaizen"').stdout || '0').trim()) || 0;
  const ok = Boolean(pid) && crashes === 0;
  console.log(`[release] ${ok ? '✓' : '⚠'} ${device}: instalada; ${pid ? `en marcha (pid ${pid})` : 'NO está en marcha (¿la cerró una automatización, como la de Home Assistant en la TV?)'}; FATAL/ANR: ${crashes}`);
  done.push(`instalada en ${device}${ok ? '' : ' (con avisos)'}`);
  return ok;
}

// ---------------------------------------------------------------------------------------------- ship
function ship() {
  const sha = opt('--audited');
  const message = opt('-m');
  if (!sha || !/^[0-9a-f]{64}$/.test(sha)) die('falta --audited con el SHA completo que aprobó el auditor (el que imprime `prepare`)');
  if (!message) die('falta -m "mensaje del commit"');
  const tag = opt('--tag');
  if (!tag && !has('--no-release')) die('falta --tag (por ejemplo v6.1.0): si no existe se crea una release nueva con --notes; si existe se sustituye su APK');
  if (tag && !/^v?\d[\w.\-]*$/.test(tag)) die('--tag tiene un formato no válido (ejemplo: v6.0.8)');
  const trailer = opt('--trailer', 'Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>');
  const devices = (opt('--devices', '') || '').split(',').map((d) => d.trim()).filter(Boolean);
  if (devices.some((d) => !/^[\w.\-]+:\d+$/.test(d))) die('--devices lleva IP:puerto separados por comas');
  const done = [];

  step('ship.js publish (commit, push sin force, verificación con ls-remote)');
  const publish = node('ship.js', ['publish', '-m', message, '--audited', sha, '--trailer', trailer]);
  if (publish.status !== 0) die('`ship.js publish` no publicó (nada se ha subido a GitHub); mira el motivo arriba', done);
  const commit = run('git', ['rev-parse', 'HEAD']).stdout.trim();
  const remote = (run('git', ['ls-remote', 'origin', 'main']).stdout || '').split(/\s/)[0];
  if (remote !== commit) die(`origin/main (${remote.slice(0, 9)}) no apunta al commit publicado (${commit.slice(0, 9)})`, done);
  done.push(`commit ${commit.slice(0, 9)} publicado y verificado en origin/main`);

  if (!has('--no-release')) release(tag, commit, done); else step('release omitido (--no-release)');

  if (!has('--no-install')) {
    step('instalación');
    if (devices.length) {
      const all = devices.map((d) => install(d, done));
      if (all.includes(false)) console.error('[release] alguna instalación no quedó limpia: revisa los avisos de arriba');
    } else {
      const deploy = node('tv.js', ['deploy']);
      done.push(deploy.status === 0 ? 'instalada en el equipo por defecto (tv.js deploy)' : 'instalación por defecto con avisos');
    }
  } else step('instalación omitida (--no-install)');

  console.log(`\n[release] TERMINADO\n  - ${done.join('\n  - ')}`);
}

if (cmd === 'prepare') prepare();
else if (cmd === 'ship') ship();
else die('uso: node tools/release.js prepare [--add f]... [--exclude f]... [--prev SHA]  |  ship --audited SHA -m "msg" --tag vX.Y.Z [--notes "novedades"] [--title t] [--devices IP:5555,IP:5555] [--no-release] [--no-install]');
