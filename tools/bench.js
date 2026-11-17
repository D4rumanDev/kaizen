#!/usr/bin/env node
/**
 * Medición repetible del arranque de Kaizen en la TV (solo lee y arranca/para la app; no toca datos).
 *
 *   node tools/bench.js start [--runs 5] [--idle 90] [--device IP:puerto]
 *
 * Por cada pasada: `am force-stop`, `am start -W` (arranque en frío: TotalTime, ThisTime y WaitTime de ActivityManager) y, tras él, el uso de CPU del proceso cada 5 s durante
 * `--idle` segundos (ticks de `/proc/PID/stat`). Al final resume la mediana, el mínimo y el máximo del arranque, y de la CPU: el pico, los segundos por encima del 50 % y cuándo baja y se queda por
 * debajo del 15 %. Da una cifra repetible; no sustituye a un Macrobenchmark.
 * Para saber QUÉ tarea del arranque pesa, mira después `node tools/tv.js logs` (líneas `Arranque: … ms` de `DebugLog.timed`).
 *
 * La TV debe estar despierta (`node tools/tv.js awake on`) y sin reproducir nada. El primer arranque tras instalar un APK nuevo es más lento (reagrupa el catálogo): descártalo
 * con una pasada previa o compara con `--runs`.
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const PACKAGE = 'app.kaizen';

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

const device = arg('--device', process.env.ADB_DEVICE || deviceFromEnvFile());
if (process.argv[2] !== 'start') { console.error('uso: node tools/bench.js start [--runs 5] [--idle 90] [--device IP:puerto]'); process.exit(2); }
if (!device) { console.error('FALLO: define ADB_DEVICE en tools/adb-device/.env o pasa --device'); process.exit(1); }
const runs = Number(arg('--runs', '5'));
const idle = Number(arg('--idle', '90'));
const STEP_S = 5;

const adb = (...a) => { const r = spawnSync('adb', ['-s', device, ...a], { encoding: 'utf8' }); return { code: r.status, out: `${r.stdout || ''}${r.stderr || ''}` }; };
const sleep = (s) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, s * 1000);
const median = (v) => { const s = [...v].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };

spawnSync('adb', ['connect', device]);
if (adb('get-state').out.trim() !== 'device') { console.error('FALLO: la TV no responde por ADB'); process.exit(1); }

// La actividad del lanzador de TV, tal como la ve el sistema.
const resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-c', 'android.intent.category.LEANBACK_LAUNCHER', PACKAGE).out.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.includes('/')).pop();
if (!resolved) { console.error('FALLO: no se encuentra la actividad de lanzamiento de', PACKAGE); process.exit(1); }

/** Ticks de CPU (usuario + sistema, 100 por segundo) que lleva gastados el proceso, de `/proc/PID/stat`; null si no se puede leer. `top` no sirve: su %CPU no cuadra con su propio TIME+. */
function ticksOf(pid) {
  const raw = adb('shell', 'cat', `/proc/${pid}/stat`).out;
  // El nombre del proceso va entre paréntesis y puede llevar espacios: se cuenta desde el último «)».
  const rest = raw.slice(raw.lastIndexOf(')') + 2).trim().split(/\s+/);
  const user = Number(rest[11]);
  const sys = Number(rest[12]);
  return Number.isFinite(user) && Number.isFinite(sys) ? user + sys : null;
}

const results = [];
for (let r = 1; r <= runs; r++) {
  adb('shell', 'am', 'force-stop', PACKAGE);
  sleep(3);
  const start = adb('shell', 'am', 'start', '-W', '-n', resolved).out;
  const total = Number((start.match(/TotalTime:\s*(\d+)/) || [])[1]);
  const wait = Number((start.match(/WaitTime:\s*(\d+)/) || [])[1]);
  const pid = adb('shell', 'pidof', PACKAGE).out.trim().split(/\s+/)[0];
  const series = [];
  let lastTicks = pid ? ticksOf(pid) : null;
  let lastAt = Date.now();
  for (let t = 0; t < idle && pid; t += STEP_S) {
    sleep(STEP_S);
    const ticks = ticksOf(pid);
    const now = Date.now();
    // % de un núcleo (puede pasar de 100): ticks gastados entre dos lecturas / tiempo real transcurrido.
    if (ticks !== null && lastTicks !== null) series.push(((ticks - lastTicks) / 100) / ((now - lastAt) / 1000) * 100);
    lastTicks = ticks;
    lastAt = now;
  }
  const peak = series.length ? Math.max(...series) : NaN;
  const hot = series.filter((c) => c > 50).length * STEP_S;
  // El último instante en que estuvo por encima del 15 %: desde ahí está tranquila.
  let settle = 0;
  series.forEach((c, i) => { if (c > 15) settle = (i + 1) * STEP_S; });
  results.push({ total, wait, peak, hot, settle, samples: series.length });
  console.log(`pasada ${r}/${runs}: arranque ${total} ms (con espera ${wait} ms) · CPU pico ${peak.toFixed(0)} % · ${hot} s por encima del 50 % · tranquila tras ${settle} s (${series.length} muestras)`);
}

const col = (k) => results.map((x) => x[k]).filter((v) => Number.isFinite(v));
const fmt = (k, unit) => `mediana ${median(col(k)).toFixed(0)}${unit} · mín ${Math.min(...col(k)).toFixed(0)} · máx ${Math.max(...col(k)).toFixed(0)}`;
console.log(`\nResumen de ${runs} arranques en frío en ${device}`);
console.log(`  arranque hasta la primera pantalla: ${fmt('total', ' ms')}`);
console.log(`  CPU pico:                           ${fmt('peak', ' %')}`);
console.log(`  segundos por encima del 50 %:       ${fmt('hot', ' s')}`);
console.log(`  segundos hasta quedar tranquila:    ${fmt('settle', ' s')}`);
console.log('Qué tarea pesa: node tools/tv.js logs  (líneas «Arranque: … ms»)');
