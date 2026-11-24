#!/usr/bin/env node
/**
 * Vigilante de la red de la TV desde el PC: cada minuto, por ADB, mide desde la propia TV hasta dónde llega y lo anota.
 * Sirve para distinguir un corte del Wi-Fi de la TV, del router, del DNS o de una web concreta.
 *
 * Uso (desde la raíz del repo):
 *   node tools/net-watch.js [--interval 60] [--hosts animeflv.one,jkanime.net,cdn6.ducvomes.com] [--out carpeta] [--device IP:puerto]
 *   node tools/net-watch.js --once          (un solo ciclo, lo enseña y sale)
 *   node tools/net-watch.js --summary       (resume lo recogido: cortes, fallos por destino y latencias)
 *
 * Cada ciclo mide, en este orden (todo con una sola orden `adb shell`):
 *   gw      ping a la puerta de enlace (el router): ¿llega la TV al router?
 *   dns     ping al primer servidor DNS de la red (p. ej. AdGuard): ¿responde?
 *   ip      ping a 1.1.1.1 (sin DNS de por medio): ¿hay salida a Internet?
 *   <web>   conexión TCP al puerto 443 de cada web de --hosts, con su nombre (DNS + conexión): ¿llega a esa web?
 *   rssi    señal del Wi-Fi (dBm)    awake  si la TV estaba despierta
 * Cada ping manda dos paquetes: el primero tras un rato en reposo suele tardar mucho más si el Wi-Fi de la TV «duerme» entre medidas.
 *
 * Los resultados van a `backups/net-watch/AAAA-MM-DD.jsonl` (una línea JSON por ciclo; `backups/` no se versiona).
 * No cambia nada en la TV: solo lanza pings y conexiones de prueba. Se para con Ctrl+C.
 */
'use strict';
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { ROOT, deviceFromEnvFile } = require('./lib/adb-device');

const argv = process.argv.slice(2);
const arg = (name, fallback) => { const i = argv.indexOf(name); return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback; };
const flag = (name) => argv.includes(name);

const OUT = path.resolve(ROOT, arg('--out', path.join('backups', 'net-watch')));
const INTERVAL_S = Math.max(10, Number(arg('--interval', 60)) || 60);
// Los nombres se pasan tal cual al shell de la TV: solo letras, cifras, puntos y guiones.
const HOSTS = arg('--hosts', 'animeflv.one,jkanime.net,cdn6.ducvomes.com').split(',').map((h) => h.trim()).filter(Boolean)
  .map((h) => (/^[A-Za-z0-9.-]+$/.test(h) ? h : fail(`host no válido en --hosts: ${h}`)));
/** Un TCP que tarda esto o más cuenta como «sin respuesta a tiempo» (el `nc -w 3` de la TV se rinde a los 3 s). */
const TCP_TIMEOUT_MS = 2900;
/** Un ping por encima de esto, en el primer o el segundo paquete, se marca como lento. */
const SLOW_MS = 300;

function fail(msg) { console.error(`FALLO: ${msg}`); process.exit(1); }

// ------------------------------------------------------------------------------------------------ Resumen

function readAll() {
  if (!fs.existsSync(OUT)) return [];
  return fs.readdirSync(OUT).filter((f) => f.endsWith('.jsonl')).sort()
    .flatMap((f) => fs.readFileSync(path.join(OUT, f), 'utf8').split('\n').filter(Boolean).map((l) => { try { return JSON.parse(l); } catch { return null; } }))
    .filter(Boolean);
}

const pct = (arr, p) => { if (!arr.length) return null; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };

/** Lo que falla en un ciclo: lista de destinos (vacía si todo va bien). */
function problems(c) {
  if (c.adb) return ['adb'];
  const out = [];
  for (const k of ['gw', 'dns', 'ip']) if (c[k] && c[k].fail) out.push(`${k}:${c[k].fail}`);
  for (const [h, v] of Object.entries(c.web || {})) if (v.fail) out.push(`${h}:${v.fail}`);
  return out;
}

function summary() {
  const cycles = readAll();
  if (!cycles.length) { console.log(`Sin datos en ${OUT}. Lanza primero: node tools/net-watch.js`); return; }
  console.log(`${cycles.length} ciclos · de ${cycles[0].t} a ${cycles[cycles.length - 1].t}`);
  const bad = cycles.filter((c) => problems(c).length);
  console.log(`ciclos con algún fallo: ${bad.length} (${(100 * bad.length / cycles.length).toFixed(1)} %)`);
  // Cortes: ciclos seguidos con fallo, con qué falló y desde cuándo.
  const runs = [];
  let cur = null;
  for (const c of cycles) {
    const p = problems(c);
    if (p.length) { if (!cur) { cur = { from: c.t, to: c.t, n: 0, what: new Set() }; runs.push(cur); } cur.to = c.t; cur.n++; p.forEach((x) => cur.what.add(x)); }
    else cur = null;
  }
  if (runs.length) console.log('\nFallos seguidos (desde → hasta · ciclos · qué):');
  for (const r of runs.slice(-40)) console.log(`  ${r.from.slice(5, 16)} → ${r.to.slice(11, 16)} · ${r.n} · ${[...r.what].join(' ')}`);
  // Qué falla más y a qué hora.
  const count = {}; const byHour = {};
  for (const c of bad) for (const x of problems(c)) { count[x] = (count[x] || 0) + 1; }
  for (const c of bad) { const h = c.t.slice(11, 13); byHour[h] = (byHour[h] || 0) + 1; }
  console.log('\nFallos por destino:', JSON.stringify(count));
  console.log('Ciclos con fallo por hora:', JSON.stringify(byHour));
  // Latencias: primer paquete (tras reposo) y segundo.
  for (const k of ['gw', 'dns', 'ip']) {
    const first = cycles.map((c) => c[k] && c[k].ms && c[k].ms[0]).filter((x) => x != null);
    const second = cycles.map((c) => c[k] && c[k].ms && c[k].ms[1]).filter((x) => x != null);
    if (first.length) console.log(`${k}: 1.º paquete mediana ${pct(first, 0.5)} ms · p95 ${pct(first, 0.95)} ms · máx ${Math.max(...first)} ms | 2.º paquete mediana ${pct(second, 0.5)} ms · p95 ${pct(second, 0.95)} ms`);
  }
  const rssi = cycles.map((c) => c.rssi).filter((x) => typeof x === 'number');
  if (rssi.length) console.log(`señal Wi-Fi: mediana ${pct(rssi, 0.5)} dBm · peor ${Math.min(...rssi)} dBm`);
}

if (flag('--summary')) { summary(); process.exit(0); }

// ------------------------------------------------------------------------------------------------ Medir

const device = arg('--device', process.env.ADB_DEVICE || deviceFromEnvFile());
if (!device) fail('define ADB_DEVICE en tools/adb-device (ver el fichero de ejemplo) o pasa --device IP:puerto');
const adb = (args, timeout = 60000) => spawnSync('adb', ['-s', device, ...args], { encoding: 'utf8', timeout });

function connect() { spawnSync('adb', ['connect', device], { encoding: 'utf8', timeout: 15000 }); }

/** El primer servidor DNS de la red de la TV (la IP que da el DHCP), para medirlo aparte. */
function dnsServer() {
  const r = adb(['shell', 'dumpsys wifi | grep -m1 -o "DNS servers: \\[[^]]*"']);
  const m = /DNS servers: \[\s*([0-9.]+)/.exec(r.stdout || '');
  return m ? m[1] : null;
}

const SCRIPT = (dns, hosts) => `
gw=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* via \\([0-9.]*\\).*/\\1/p')
p(){ r=$(ping -c2 -i0.3 -W2 "$2" 2>&1); v=$(echo "$r" | sed -n 's/.*time=\\([0-9.]*\\) ms.*/\\1/p' | tr '\\n' ','); if [ -n "$v" ]; then echo "$1=$v"; elif echo "$r" | grep -qi 'unknown host'; then echo "$1=dns"; else echo "$1=timeout"; fi; }
c(){ a=$(date +"%s %N"); nc -w 3 "$2" 443 </dev/null >/dev/null 2>&1; rc=$?; b=$(date +"%s %N"); echo "web:$2=$rc,$a,$b"; }
[ -n "$gw" ] && p gw "$gw" || echo gw=none
${dns ? `p dns ${dns}` : 'echo dns=none'}
p ip 1.1.1.1
${hosts.map((h) => `c web ${h}`).join('\n')}
echo rssi=$(dumpsys wifi | grep -m1 -o 'RSSI: -*[0-9]*' | tr -dc '0-9-')
echo awake=$(dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*' | cut -d= -f2)
`;

function parsePing(v) {
  if (v === 'dns' || v === 'timeout' || v === 'none') return { fail: v };
  const ms = v.split(',').filter(Boolean).map(Number);
  return { ms, slow: ms.some((x) => x >= SLOW_MS) || undefined };
}

function cycle(dns) {
  const stamp = new Date();
  const local = new Date(stamp.getTime() - stamp.getTimezoneOffset() * 60000).toISOString().slice(0, 19).replace('T', ' ');
  const r = adb(['shell', SCRIPT(dns, HOSTS)], 45000);
  if (r.status !== 0 || !r.stdout) { connect(); // Etiqueta fija, nunca el texto de adb: lleva la dirección del dispositivo y esto se guarda en disco.
    return { t: local, adb: r.error && r.error.code === 'ETIMEDOUT' ? 'timeout' : 'sin respuesta' }; }
  const row = { t: local, web: {} };
  for (const line of r.stdout.split(/\r?\n/)) {
    const m = /^([^=]+)=(.*)$/.exec(line.trim());
    if (!m) continue;
    const [, key, val] = m;
    if (key.startsWith('web:')) {
      // El reloj de la TV da segundos y nanosegundos por separado (pegarlos falla si los nanosegundos salen sin ceros a la izquierda): la resta se hace aquí.
      const [rc, a, b] = val.split(',');
      const [sa, na] = (a || '').split(' ').map(Number);
      const [sb, nb] = (b || '').split(' ').map(Number);
      const ms = Math.max(0, Math.round((sb - sa) * 1000 + (nb - na) / 1e6));
      row.web[key.slice(4)] = Number(rc) === 0 ? { ms } : { fail: ms >= TCP_TIMEOUT_MS ? 'timeout' : 'rechazo-o-dns', ms };
    } else if (['gw', 'dns', 'ip'].includes(key)) row[key] = parsePing(val);
    else if (key === 'rssi') row.rssi = val === '' ? undefined : Number(val);
    else if (key === 'awake') row.awake = val;
  }
  return row;
}

function line(c) {
  if (c.adb) return `${c.t.slice(11)} ADB no responde (${c.adb})`;
  const f = (k, name) => (c[k] ? (c[k].fail ? `${name} ✗${c[k].fail}` : `${name} ${c[k].ms.join('/')}ms${c[k].slow ? '!' : ''}`) : '');
  const webs = Object.entries(c.web).map(([h, v]) => (v.fail ? `${h} ✗${v.fail}(${v.ms}ms)` : `${h} ${v.ms}ms`)).join(' · ');
  const mark = problems(c).length ? '!! ' : '';
  return `${mark}${c.t.slice(11)} ${[f('gw', 'router'), f('dns', 'dns'), f('ip', '1.1.1.1')].filter(Boolean).join(' · ')} · ${webs} · ${c.rssi ?? '?'} dBm · ${c.awake ?? '?'}`;
}

function save(c) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.appendFileSync(path.join(OUT, `${c.t.slice(0, 10)}.jsonl`), JSON.stringify(c) + '\n');
}

connect();
const dns = dnsServer();
console.log(`[net-watch] dispositivo conectado · DNS de la red: ${dns || 'no se pudo leer'} · webs: ${HOSTS.join(', ')} · cada ${INTERVAL_S} s · datos en ${path.relative(ROOT, OUT)}`);

if (flag('--once')) { const c = cycle(dns); console.log(line(c)); process.exit(0); }

let stopping = false;
process.on('SIGINT', () => { stopping = true; console.log('\n[net-watch] parado. Resumen: node tools/net-watch.js --summary'); process.exit(0); });

(function loop() {
  const started = Date.now();
  const c = cycle(dns);
  save(c);
  console.log(line(c));
  if (!stopping) setTimeout(loop, Math.max(1000, INTERVAL_S * 1000 - (Date.now() - started)));
})();
