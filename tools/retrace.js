#!/usr/bin/env node
/**
 * Lee las trazas ofuscadas (R8) del registro de la TV.
 *
 *   node tools/retrace.js save                 guarda el mapping.txt de la última compilación de Tv en backups/mapping/<id>.txt (lo hace también `ship.js check`)
 *   node tools/retrace.js trace RUTA_DEBUG_LOG  traduce cada traza con el mapping de la compilación que la generó (el id sale de `r8-map-id-XXXXXXXX` en cada línea)
 *
 * Sin el mapping de la compilación, una traza no se puede leer (`a.b.c(r8-map-id-…:12)`): por eso se guarda uno por compilación. Usa el retrace del SDK de Android
 * (cmdline-tools/latest/bin/retrace). Con ANDROID_HOME o ANDROID_SDK_ROOT se busca ahí; si no, en la ruta habitual de Windows.
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MAPPING = path.join(ROOT, 'app', 'build', 'outputs', 'mapping', 'tv', 'mapping.txt');
const SAVED = path.join(ROOT, 'backups', 'mapping');

function mapId(text) {
  const found = /pg_map_id:\s*([0-9a-f]{8})/.exec(text.slice(0, 4000));
  return found ? found[1] : null;
}

/** Copia el mapping de la última compilación a backups/mapping/<id>.txt; devuelve el id o null si no hay mapping. */
function save() {
  if (!fs.existsSync(MAPPING)) return null;
  const id = mapId(fs.readFileSync(MAPPING, 'utf8'));
  if (!id) return null;
  fs.mkdirSync(SAVED, { recursive: true });
  const target = path.join(SAVED, `${id}.txt`);
  if (!fs.existsSync(target)) fs.copyFileSync(MAPPING, target);
  return id;
}

function retraceTool() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(os.homedir(), 'AppData', 'Local', 'Android', 'Sdk');
  const bin = path.join(sdk, 'cmdline-tools', 'latest', 'bin', process.platform === 'win32' ? 'retrace.bat' : 'retrace');
  return fs.existsSync(bin) ? bin : null;
}

/** El entorno para lanzar retrace: si JAVA_HOME apunta a un JDK que ya no existe, usa el JDK más nuevo de Eclipse Adoptium (sin cambiar nada en tu configuración). */
function javaEnv() {
  const java = process.platform === 'win32' ? 'java.exe' : 'java';
  const ok = (home) => home && fs.existsSync(path.join(home, 'bin', java));
  if (ok(process.env.JAVA_HOME)) return process.env;
  const base = process.platform === 'win32' ? path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Eclipse Adoptium') : '/usr/lib/jvm';
  const homes = fs.existsSync(base) ? fs.readdirSync(base).filter((d) => ok(path.join(base, d))).sort().reverse() : [];
  return homes.length ? { ...process.env, JAVA_HOME: path.join(base, homes[0]) } : process.env;
}

function trace(logPath) {
  if (!logPath || !fs.existsSync(logPath)) { console.error('uso: node tools/retrace.js trace RUTA_DEBUG_LOG'); process.exit(2); }
  const tool = retraceTool();
  if (!tool) { console.error('no encuentro retrace en el SDK de Android (cmdline-tools/latest/bin)'); process.exit(2); }
  const lines = fs.readFileSync(logPath, 'utf8').split(/\r?\n/);
  const ids = [...new Set(lines.map((l) => /r8-map-id-([0-9a-f]{8})/.exec(l)).filter(Boolean).map((m) => m[1]))];
  if (!ids.length) { console.log('el registro no tiene trazas ofuscadas'); return; }
  for (const id of ids) {
    const mapping = path.join(SAVED, `${id}.txt`);
    if (!fs.existsSync(mapping)) { console.log(`[retrace] ${id}: sin mapping guardado (compilación anterior a este guardado): sus trazas no se pueden leer`); continue; }
    const part = path.join(os.tmpdir(), `kaizen-trace-${id}.txt`);
    fs.writeFileSync(part, lines.filter((l) => l.includes(`r8-map-id-${id}`) || /^\d{4}-\d\d-\d\d /.test(l) || /^[\w.$]+(Exception|Error)/.test(l)).join('\n'));
    const quote = (p) => (process.platform === 'win32' ? `"${p}"` : p);
    const r = spawnSync(quote(tool), [quote(mapping), quote(part)], { encoding: 'utf8', shell: process.platform === 'win32', maxBuffer: 1 << 28, env: javaEnv() });
    console.log(`\n===== compilación ${id} =====\n${r.stdout || r.stderr}`);
  }
}

if (require.main === module) {
  const [command, arg] = process.argv.slice(2);
  if (command === 'save') { const id = save(); console.log(id ? `[retrace] mapping ${id} guardado en backups/mapping` : '[retrace] no hay mapping de Tv (compila primero)'); }
  else if (command === 'trace') trace(arg);
  else { console.error('uso: node tools/retrace.js save | trace RUTA_DEBUG_LOG'); process.exit(2); }
}

module.exports = { save };
