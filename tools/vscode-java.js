#!/usr/bin/env node
/**
 * Comprueba (y con --fix arregla) la configuración de Java de VS Code: «The java.jdt.ls.java.home variable defined in Visual Studio Code
 * settings (…jdk-17.0.20.101-hotspot) does not point to a JDK».
 *
 * Causa: el ajuste de usuario fijaba la carpeta de una versión concreta del JDK (`jdk-17.0.20.101-hotspot`); al actualizar Temurin el instalador
 * deja la carpeta nueva (`jdk-17.0.21.9-hotspot`) y la vieja queda a medias, y el ajuste apunta a un JDK que ya no existe.
 *
 * Arreglo duradero: no fijar ninguna versión. Sin `java.jdt.ls.java.home` ni `java.configuration.runtimes`, la extensión de Java usa la variable
 * de entorno `JAVA_HOME`, que el instalador de Temurin mantiene al día en cada actualización. Esta herramienta quita esas dos claves si están
 * fijadas a una carpeta con versión (o a una que no es un JDK) y comprueba que `JAVA_HOME` apunta a un JDK de verdad.
 *
 * Uso (desde la raíz del repo):
 *   node tools/vscode-java.js          (solo informa; sale con 1 si hay algo que arreglar)
 *   node tools/vscode-java.js --fix    (copia el fichero de ajustes a backups/vscode/ y quita las claves fijadas)
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const FIX = process.argv.includes('--fix');
const KEYS = ['java.jdt.ls.java.home', 'java.configuration.runtimes'];

const settingsPath = path.join(process.env.APPDATA || '', 'Code', 'User', 'settings.json');
if (!process.env.APPDATA || !fs.existsSync(settingsPath)) {
  console.log(`[vscode-java] no encuentro los ajustes de usuario de VS Code (${settingsPath})`);
  process.exit(0);
}

function isJdk(dir) {
  if (!dir) return false;
  const clean = dir.replace(/[\\/]+$/, '');
  return fs.existsSync(path.join(clean, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'));
}

const raw = fs.readFileSync(settingsPath, 'utf8');
let settings;
try { settings = JSON.parse(raw); } catch (e) { console.log(`[vscode-java] ${settingsPath} no es JSON puro (¿comentarios?): ${e.message}; no se toca`); process.exit(2); }

const pins = [];
if (typeof settings[KEYS[0]] === 'string') pins.push({ key: KEYS[0], dir: settings[KEYS[0]] });
for (const r of settings[KEYS[1]] || []) if (r && typeof r.path === 'string') pins.push({ key: KEYS[1], dir: r.path });

let problems = 0;
for (const p of pins) {
  const versioned = /jdk-?\d+[.\d+]*[-\w]*/i.test(path.basename(p.dir.replace(/[\\/]+$/, '')));
  const ok = isJdk(p.dir);
  console.log(`[vscode-java] ${p.key} = ${p.dir}  ${ok ? 'JDK válido' : 'NO es un JDK'}${versioned ? ' · fijado a una versión' : ''}`);
  if (!ok || versioned) problems++;
}
if (!pins.length) console.log('[vscode-java] ningún ajuste de Java fijado a una carpeta: se usa JAVA_HOME');

const home = process.env.JAVA_HOME || '';
const homeOk = isJdk(home);
console.log(`[vscode-java] JAVA_HOME = ${home || '(sin definir)'}  ${homeOk ? 'JDK válido' : 'NO es un JDK'}`);
if (!homeOk) problems++;

if (!FIX) {
  if (problems) console.log('[vscode-java] hay algo que arreglar: node tools/vscode-java.js --fix');
  process.exit(problems ? 1 : 0);
}
if (!homeOk) { console.log('[vscode-java] JAVA_HOME no apunta a un JDK: arréglalo antes (sin él, VS Code no tendría de dónde sacar Java). No se toca nada.'); process.exit(1); }

// Solo se quita lo malo (carpeta con versión o que no es un JDK); un JDK válido sin versión, o las demás entradas de runtimes, se respetan.
const bad = (dir) => !isJdk(dir) || /jdk-?\d+[.\d+]*[-\w]*/i.test(path.basename(String(dir).replace(/[\\/]+$/, '')));
const touched = [];
if (typeof settings[KEYS[0]] === 'string' && bad(settings[KEYS[0]])) { delete settings[KEYS[0]]; touched.push(KEYS[0]); }
if (Array.isArray(settings[KEYS[1]])) {
  const kept = settings[KEYS[1]].filter((r) => !(r && typeof r.path === 'string' && bad(r.path)));
  if (kept.length !== settings[KEYS[1]].length) { touched.push(KEYS[1]); if (kept.length) settings[KEYS[1]] = kept; else delete settings[KEYS[1]]; }
}
if (!touched.length) { console.log('[vscode-java] nada que quitar'); process.exit(0); }
const dir = path.join(ROOT, 'backups', 'vscode');
fs.mkdirSync(dir, { recursive: true });
const backup = path.join(dir, `settings-${new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19)}.json`);
fs.writeFileSync(backup, raw);
if (fs.readFileSync(backup, 'utf8') !== raw) { console.log('[vscode-java] la copia de seguridad no coincide: no se toca nada'); process.exit(1); }
// Escritura atómica: fichero temporal y renombrado, para no dejar los ajustes a medias.
fs.writeFileSync(settingsPath + '.tmp', JSON.stringify(settings, null, 2) + '\n');
fs.renameSync(settingsPath + '.tmp', settingsPath);
JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
console.log(`[vscode-java] quitado: ${touched.join(', ')} (copia en ${path.relative(ROOT, backup)}). VS Code usará JAVA_HOME; recarga la ventana (Developer: Reload Window).`);
