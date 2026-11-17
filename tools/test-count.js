#!/usr/bin/env node
/**
 * Cuenta las pruebas de la última ejecución de `:app:testDebugUnitTest` leyendo los informes de `app/build/test-results/testDebugUnitTest/`
 * (el número que se da en un informe sale de aquí, no de la memoria: ya se dio una cifra equivocada dos veces).
 *
 * Uso: node tools/test-count.js        -> "pruebas=974 saltadas=1 fallos=0"; sale con 1 si hay fallos.
 */
const fs = require('fs');
const path = require('path');

const dir = path.resolve(__dirname, '..', 'app', 'build', 'test-results', 'testDebugUnitTest');
if (!fs.existsSync(dir)) { console.error('no hay resultados: ejecuta primero ./gradlew :app:testDebugUnitTest'); process.exit(2); }
let tests = 0, skipped = 0, failed = 0;
for (const name of fs.readdirSync(dir).filter((f) => f.endsWith('.xml'))) {
  const head = fs.readFileSync(path.join(dir, name), 'utf8').slice(0, 2000);
  const m = head.match(/tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"/);
  if (!m) continue;
  tests += Number(m[1]); skipped += Number(m[2]); failed += Number(m[3]) + Number(m[4]);
}
console.log(`pruebas=${tests} saltadas=${skipped} fallos=${failed}`);
process.exit(failed ? 1 : 0);
