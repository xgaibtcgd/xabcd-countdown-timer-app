// Holds tools/preview/route.js against the real Route.java.
//
// The preview reimplements the route generator in JavaScript because the app's random
// numbers were made 32-bit for exactly that -- but "reproduces exactly" is a claim, and
// an unchecked claim about a pseudo-random sequence is the kind that stays true right up
// until someone changes a constant. tools/ExportScreens.java writes a table of real
// paths into screens.json; this compares every one of them against what the preview
// would generate, and fails loudly on the first difference.
//
//   node tools/routeproof.cjs
'use strict';
const path = require('path');
const fs = require('fs');
const PREV = path.join(__dirname, 'preview');
const { routeBuildPath } = require(path.join(PREV, 'route.js'));
const screens = JSON.parse(fs.readFileSync(path.join(PREV, 'screens.json'), 'utf8'));

const table = screens.routeProof;
if (!Array.isArray(table) || table.length === 0) {
  console.error('FAIL: screens.json has no routeProof table -- rerun tools/buildpreview.sh');
  process.exit(1);
}

let bad = 0;
for (const row of table) {
  const mine = routeBuildPath(row.seed, row.cols, row.rows);
  const want = row.path;
  if (mine.length !== want.length || mine.some((v, i) => v !== want[i])) {
    bad++;
    console.error(`FAIL: ${row.cols}x${row.rows} seed ${row.seed}\n`
                + `  java: ${want.join(',')}\n  js:   ${mine.join(',')}`);
  }
}
if (bad) {
  console.error(`FAIL: the preview's route generator differs from Route.java on `
              + `${bad} of ${table.length} cases`);
  process.exit(1);
}
console.log(`==> routeproof: ${table.length} paths identical to Route.java`);
