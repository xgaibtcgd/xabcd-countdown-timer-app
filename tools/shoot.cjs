// Renders every preview screen to a PNG with headless Chromium, so type and button
// changes can actually be looked at without an emulator. The page it drives,
// tools/preview/_screens_render.html, loads the REAL bundled .ttf files rather than
// Google's copies, because the point of the exercise is usually metrics.
//
//   node tools/shoot.cjs <output-dir>
//
// Chromium and Playwright are preinstalled in the sandbox this was written in; adjust
// the require() path if yours differs. Not part of the build.
const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const path = require('path');
const PREV = path.join(__dirname, 'preview');
const OUT  = process.argv[2] || '.';
const keys = ['home','adventure','complete','picker','time','grownups','editroutine'];

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 2100 } });
  const errs = [];
  page.on('pageerror', e => errs.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await page.goto('file://' + path.join(PREV, '_screens_render.html'));
  await page.evaluate(() => window.READY);
  await page.evaluate(async () => { await document.fonts.load('800 78px "Baloo 2"'); await document.fonts.load('800 30px "Nunito"'); await document.fonts.load('400 30px "Nunito"'); await document.fonts.ready; });
  await page.waitForTimeout(1200);
  for (const k of keys) {
    await page.evaluate(([k]) => window.RENDER(k, 0, 1, 6.0), [k]);
    const el = await page.$('#out');
    await el.screenshot({ path: path.join(OUT, k + '.png') });
  }
  if (errs.length) { console.error('PAGE ERRORS:\n' + errs.join('\n')); }
  else console.log('rendered ' + keys.length + ' screens');
  await browser.close();
})();
