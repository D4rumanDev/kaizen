'use strict';
/**
 * El dispositivo ADB de las herramientas: `ADB_DEVICE` del entorno o del fichero de entorno de `tools/adb-device/` (no se versiona).
 * Una sola definición para `tv.js` y `net-watch.js`.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');

/** IP:puerto del fichero de entorno de la carpeta del dispositivo, o null si no existe o no lo define. */
function deviceFromEnvFile(root = ROOT) {
  const file = path.join(root, 'tools', 'adb-device', '.env');
  if (!fs.existsSync(file)) return null;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*ADB_DEVICE\s*=\s*(.+)$/);
    if (m) return m[1].trim();
  }
  return null;
}

module.exports = { ROOT, deviceFromEnvFile };
