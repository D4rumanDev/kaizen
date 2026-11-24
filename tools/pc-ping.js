#!/usr/bin/env node
/**
 * Pings desde el PC (no desde la TV) para comparar con `net-watch.js`: si el PC también pierde la salida a Internet a las mismas horas que la TV,
 * el corte es del router o del operador y no de la TV.
 *
 * Uso: node tools/pc-ping.js [--interval 2] [--target 1.1.1.1] [--out carpeta]
 * Anota una línea en `backups/net-watch/pc-ping-AAAA-MM-DD.log` por cada fallo y cada recuperación (con cuánto duró), y una cada hora para saber que sigue vivo.
 * Solo manda un ping cada pocos segundos. Se para con Ctrl+C.
 */
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { ROOT } = require('./lib/adb-device');

const argv = process.argv.slice(2);
const arg = (name, fallback) => { const i = argv.indexOf(name); return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback; };
const INTERVAL_MS = Math.max(1, Number(arg('--interval', 2))) * 1000;
const TARGET = arg('--target', '1.1.1.1');
if (!/^[A-Za-z0-9][A-Za-z0-9.-]*$/.test(TARGET)) { console.error(`FALLO: destino no válido: ${TARGET}`); process.exit(1); }
const OUT = path.resolve(ROOT, arg('--out', path.join('backups', 'net-watch')));

const stamp = (d = new Date()) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19).replace('T', ' ');

function note(text) {
  const line = `${stamp()} ${text}`;
  console.log(line);
  fs.mkdirSync(OUT, { recursive: true });
  fs.appendFileSync(path.join(OUT, `pc-ping-${line.slice(0, 10)}.log`), line + '\n');
}

/** Un ping de Windows (`-n 1`, espera 1,5 s): resuelve con los milisegundos o con null si no hubo respuesta. */
function ping() {
  return new Promise((resolve) => {
    const p = spawn('ping', ['-n', '1', '-w', '1500', TARGET], { windowsHide: true });
    let out = '';
    p.stdout.on('data', (d) => { out += d; });
    p.on('error', () => resolve(null));
    p.on('close', () => { const m = /(?:tiempo|time)[=<]\s*(\d+)\s*ms/i.exec(out); resolve(m ? Number(m[1]) : null); });
  });
}

let downSince = null;
let lastBeat = Date.now();
let okCount = 0;
note(`[pc-ping] vigilando ${TARGET} cada ${INTERVAL_MS / 1000} s`);

(async function loop() {
  const ms = await ping();
  const now = Date.now();
  if (ms === null && downSince === null) { downSince = now; note(`CAE ${TARGET} sin respuesta`); }
  else if (ms !== null && downSince !== null) { note(`VUELVE ${TARGET} tras ${Math.round((now - downSince) / 1000)} s (${ms} ms)`); downSince = null; }
  if (ms !== null) okCount++;
  if (now - lastBeat >= 3600 * 1000) { note(`sigue vivo: ${okCount} respuestas en la última hora`); lastBeat = now; okCount = 0; }
  setTimeout(loop, INTERVAL_MS);
})();

process.on('SIGINT', () => { note('[pc-ping] parado'); process.exit(0); });
