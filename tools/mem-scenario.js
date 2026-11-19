#!/usr/bin/env node
/**
 * Mide la memoria de Kaizen en una tele (`dumpsys meminfo`) en un escenario repetible, para comparar antes y después de un cambio.
 *
 * Uso (desde la raíz del repo):
 *     node tools/mem-scenario.js --device IP:puerto [--label antes] [--rows 60]
 *
 * Escenario (con el mando simulado por `input keyevent`; no pulsa OK sobre ninguna tarjeta, así que no reproduce ni cambia nada del usuario):
 *   1. despierta la pantalla si estaba dormida, cierra la app, la abre y espera 45 s            → «reposo»
 *   2. Últimos vistos y baja `--rows` filas (por defecto 60, unas 300 tarjetas) con 350 ms entre pasos → «tras scroll»
 *   3. pulsa Inicio (la app queda en segundo plano) y espera 10 s                                → «segundo plano»
 *   4. vuelve a abrir la app y espera 10 s                                                       → «al volver»
 *   5. si la pantalla estaba dormida al empezar, la deja dormida.
 * De cada punto anota los MB de PSS total, heap de Java y heap nativo. Guarda el resultado en `backups/mem-<etiqueta>-<equipo>.json` (carpeta ignorada por git).
 * Limitaciones: una sola pasada por equipo (el PSS varía unos MB según cuándo corra el GC); no mide el reproductor (reproducir cambiaría el historial del usuario);
 * en la TV, la automatización de Home Assistant puede cerrar la app durante la prueba (el script lo avisa si el proceso desaparece).
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const arg = (name, fallback) => { const i = process.argv.indexOf(name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; };
const device = arg('--device', null);
const label = arg('--label', 'medida');
if (!/^[\w-]+$/.test(label)) { console.error('--label solo admite letras, números, guion y guion bajo'); process.exit(1); }
const rows = Number(arg('--rows', '60')) || 60;
if (!device || !/^[\w.\-]+:\d+$/.test(device)) { console.error('uso: node tools/mem-scenario.js --device IP:puerto [--label antes] [--rows 60]'); process.exit(1); }

const adb = (...args) => spawnSync('adb', ['-s', device, ...args], { encoding: 'utf8', timeout: 60000 });
const sh = (cmd) => adb('shell', cmd).stdout || '';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const key = (code, wait = 350) => { adb('shell', 'input', 'keyevent', code); sleep(wait); };

const first = (text, re) => { const m = text.match(re); return m ? Number(m[1]) : null; };
const mb = (kb) => (kb == null ? null : Math.round(kb / 1024));

let lastPid = null;
function snapshot(name) {
  const pid = sh('pidof app.kaizen').trim();
  if (!pid) { console.log(`  ${name.padEnd(16)} la app NO está en marcha (¿la cerró otra automatización?)`); return { name, running: false }; }
  // La lectura puede fallar un instante si la app está ocupada: se reintenta una vez.
  let text = sh('dumpsys meminfo app.kaizen');
  if (!/Java Heap:/.test(text)) { sleep(3000); text = sh('dumpsys meminfo app.kaizen'); }
  const result = {
    name, running: true, pid,
    pssMB: mb(first(text, /TOTAL PSS:\s+(\d+)/) ?? first(text, /^\s*TOTAL:?\s+(\d+)/m)),
    javaMB: mb(first(text, /Java Heap:\s+(\d+)/)),
    nativeMB: mb(first(text, /Native Heap:\s+(\d+)/)),
  };
  const restarted = lastPid && lastPid !== pid ? '   ⚠ el proceso se reinició (otro pid): la medida no es comparable' : '';
  lastPid = pid;
  console.log(`  ${name.padEnd(16)} PSS ${String(result.pssMB).padStart(4)} MB   Java ${String(result.javaMB).padStart(4)} MB   nativo ${String(result.nativeMB).padStart(4)} MB   (pid ${pid})${restarted}`);
  return result;
}

if (!/device$/m.test(spawnSync('adb', ['devices'], { encoding: 'utf8' }).stdout.split('\n').filter((l) => l.startsWith(device)).join('\n'))) {
  adb('connect', device);
}
const wasAsleep = /mWakefulness=Asleep/.test(sh('dumpsys power'));
console.log(`[mem] ${device} · ${label} · ${rows} filas${wasAsleep ? ' · pantalla dormida: se despierta y se vuelve a dormir al acabar' : ''}`);

const launch = () => adb('shell', 'monkey', '-p', 'app.kaizen', '-c', 'android.intent.category.LEANBACK_LAUNCHER', '1');
if (wasAsleep) { key('KEYCODE_WAKEUP', 2500); }
adb('shell', 'am', 'force-stop', 'app.kaizen'); sleep(1500);
launch(); sleep(45000);
const out = [snapshot('reposo')];

// Menú lateral → Últimos vistos → contenido; luego baja fila a fila.
key('KEYCODE_DPAD_LEFT'); key('KEYCODE_DPAD_LEFT'); key('KEYCODE_DPAD_DOWN', 1500); key('KEYCODE_DPAD_RIGHT', 3000);
for (let i = 0; i < rows; i++) key('KEYCODE_DPAD_DOWN');
sleep(2000);
out.push(snapshot('tras scroll'));

key('KEYCODE_HOME', 10000);
out.push(snapshot('segundo plano'));

launch(); sleep(10000);
out.push(snapshot('al volver'));

if (wasAsleep) adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP');
const dir = path.join(__dirname, '..', 'backups');
fs.mkdirSync(dir, { recursive: true });
const file = path.join(dir, `mem-${label}-${device.replace(/[:.]/g, '_')}.json`);
fs.writeFileSync(file, JSON.stringify({ device, label, rows, at: new Date().toISOString(), points: out }, null, 2));
console.log(`[mem] guardado en ${path.relative(process.cwd(), file)}`);
