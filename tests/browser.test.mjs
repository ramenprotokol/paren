// End-to-end check in real headless Chrome against dist/, served with the
// production Content-Security-Policy. It drives the page like a learner
// would and fails on any console error, uncaught exception or CSP report.
// Skips if Chrome is missing (set CHROME_PATH), unless REQUIRE_BROWSER=1.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { serve } from '../scripts/serve.mjs';
import { findChrome, launchChrome } from '../scripts/cdp.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const chromePath = findChrome();
const skip = chromePath ? false : process.env.REQUIRE_BROWSER === '1' ? false : 'Chrome not found (set CHROME_PATH)';
if (!chromePath && process.env.REQUIRE_BROWSER === '1') {
  test('a browser is available', () => assert.fail('REQUIRE_BROWSER=1 but Chrome was not found'));
}

// Web fonts come from a third party; being offline is not an app error.
const appProblems = (list) => list.filter((p) => !/fonts\.(googleapis|gstatic)\.com/.test(`${p.text} ${p.url ?? ''}`));
const text = (sel) => `document.querySelector(${JSON.stringify(sel)}).textContent`;
const ready = "document.documentElement.dataset.state === 'ready'";

async function withSite(fn) {
  const server = await serve(join(root, 'dist'));
  const chrome = await launchChrome(chromePath);
  try {
    await fn(`http://127.0.0.1:${server.address().port}/`, chrome);
  } finally {
    await chrome.close();
    server.close();
  }
}

async function run(page, src) {
  await page.evaluate(`(() => { const t = document.getElementById('src'); t.value = ${JSON.stringify(src)};
    t.dispatchEvent(new Event('input')); document.getElementById('run').click(); return true; })()`);
}

test('desktop: step through, scrub, presets, errors, share link', { skip, timeout: 120000 }, async () => {
  await withSite(async (url, chrome) => {
    const page = await chrome.openPage({ width: 1280, height: 800 });
    await page.navigate(url);
    await page.waitFor(ready);

    // First view: the fib preset at step 0, one redex highlighted.
    assert.equal(await page.evaluate(text('#counter')), 'step 0 of 71');
    assert.equal(await page.evaluate(text('#caption-text')), 'define fib as a function');
    assert.equal(await page.evaluate("document.querySelectorAll('#tree .redex').length"), 1);
    assert.ok(await page.evaluate("document.querySelectorAll('#tree .card').length") > 3);

    // Keys step forward and back; End and Home jump.
    await page.key('ArrowRight');
    assert.equal(await page.evaluate(text('#counter')), 'step 1 of 71');
    assert.equal(await page.evaluate(text('#caption-text')), 'enter fib with n = 4');
    await page.key('ArrowRight');
    assert.equal(await page.evaluate("document.querySelector('#tree .card.scope .tab').textContent"), 'fibn = 4');
    assert.equal(await page.evaluate(text('#caption-text')), 'look up n → 4');
    assert.ok(await page.evaluate("!!document.querySelector('.icard .row.found')"), 'the binding being looked up is marked');
    await page.key('ArrowLeft');
    assert.equal(await page.evaluate(text('#counter')), 'step 1 of 71');
    await page.key('End');
    assert.equal(await page.evaluate(text('#caption-text')), 'done → 3');
    assert.equal(await page.evaluate("document.querySelector('.form.result').textContent"), '3');
    assert.equal(await page.evaluate("document.getElementById('fwd').disabled"), true);
    await page.key('Home');
    assert.equal(await page.evaluate(text('#counter')), 'step 0 of 71');

    // The address bar keeps the expression and step (debounced).
    await page.key('ArrowRight');
    await page.waitFor("location.hash.includes('s=1')", 3000);

    // A preset button, then the scrubber.
    await page.evaluate("document.querySelector('[data-preset=\"reduce\"]').click(), true");
    assert.equal(await page.evaluate(text('#counter')), 'step 0 of 9');
    await page.evaluate("(() => { const s = document.getElementById('scrub'); s.value = '4'; s.dispatchEvent(new Event('input')); return true; })()");
    assert.equal(await page.evaluate(text('#caption-text')), 'reduce calls + on 2 and 3');

    // Play runs by itself and stops at the end.
    await page.evaluate("document.querySelector('[data-preset=\"let\"]').click(), true");
    await page.evaluate("document.getElementById('play').click(), true");
    assert.equal(await page.evaluate("document.getElementById('play').getAttribute('aria-pressed')"), 'true');
    await page.evaluate("document.getElementById('play').click(), true");
    assert.equal(await page.evaluate("document.getElementById('play').getAttribute('aria-pressed')"), 'false');

    // A closure keeps its defining frame alive.
    await page.evaluate("document.querySelector('[data-preset=\"closure\"]').click(), true");
    for (let i = 0; i < 7; i++) await page.key('ArrowRight');
    assert.equal(await page.evaluate(text('#caption-text')), 'look up n → 5');
    assert.match(await page.evaluate(text('.icard.remembered')), /make-adder.*kept by a closure/);

    // Bad input: reader error, unsupported form, runtime error, too long.
    await run(page, '(+ 1 2');
    assert.equal(await page.evaluate("document.getElementById('src-error').hidden"), false);
    assert.match(await page.evaluate(text('#src-error')), /^Couldn't read that: .*EOF/);
    assert.match(await page.evaluate(text('#tree .slip')), /Reader error/);
    await page.evaluate("window.dispatchEvent(new Event('resize')), true");
    await new Promise((r) => setTimeout(r, 250));
    assert.match(await page.evaluate(text('#tree .slip')), /Reader error/, 'the message survives a re-render');
    assert.match(await page.evaluate("document.getElementById('caption').className"), /stopped/);
    assert.equal(await page.evaluate("document.getElementById('fwd').disabled"), true);

    await run(page, '(loop [i 0] i)');
    assert.match(await page.evaluate(text('#src-error')), /loop isn't in paren's teaching subset/);

    await run(page, '(+ 1 :a)');
    assert.equal(await page.evaluate("document.getElementById('src-error').hidden"), true);
    await page.key('End');
    assert.match(await page.evaluate(text('#caption-text')), /\+ works on numbers, but got :a/);
    assert.match(await page.evaluate("document.getElementById('caption').className"), /stopped/);

    await run(page, `(+ 1 ${' '.repeat(2000)}2)`);
    assert.match(await page.evaluate(text('#src-error')), /paren reads up to 2,000/);
    assert.match(await page.evaluate(text('#count')), /2,007 \/ 2,000/);

    await run(page, '(defn down [n] (down (inc n))) (down 0)');
    await page.key('End');
    assert.match(await page.evaluate(text('#caption-text')), /recursion cap/);

    assert.deepEqual(appProblems(page.problems), []);
    await page.close();

    // A shared link opens the same expression at the same step.
    const shared = await chrome.openPage({ width: 1280, height: 800 });
    const src = '(map (fn [x] (* x x)) [1 2 3])';
    await shared.navigate(`${url}#e=${encodeURIComponent(src)}&s=3`);
    await shared.waitFor(ready);
    assert.equal(await shared.evaluate("document.getElementById('src').value"), src);
    assert.match(await shared.evaluate(text('#counter')), /^step 3 of \d+$/);
    assert.deepEqual(appProblems(shared.problems), []);
    await shared.close();
  });
});

// A share link runs its expression as the page loads, so a crafted one must
// not be able to hang a visitor's tab. Each of these once could (or showed
// "step NaN"); now each must settle within a few seconds.
const withinMs = (ms, promise, what) =>
  Promise.race([promise, new Promise((_, reject) => setTimeout(() => reject(new Error(`${what} took over ${ms} ms`)), ms))]);

test('hostile or malformed share links settle fast with a clear message', { skip, timeout: 120000 }, async () => {
  await withSite(async (url, chrome) => {
    const hostile = [
      ['(range 0 10 ##NaN)', /^range needs finite numbers, but got ##NaN$/],
      ['(range 0 10 (/ 0 0))', /^range needs finite numbers, but got ##NaN$/],
      ['(range 100000000000000000 100000000000000400 0.5)', /more than 1000 numbers/],
      ['(defn g [v n] (if (= n 0) v (g [v v] (dec n)))) (= (g [] 40) (g [] 40))',
        /^This vector would hold more than 10,000 items/],
      ['{(+ 1 1) 1 2 3}', /^Duplicate key: 2$/],
    ];
    for (const [src, message] of hostile) {
      const page = await chrome.openPage({ width: 1280, height: 800 });
      await page.navigate(`${url}#e=${encodeURIComponent(src)}&s=99999`);
      await withinMs(5000, page.waitFor(ready, 5000), `loading a link to ${src}`);
      // s=99999 is clamped to the last step: the one that stopped.
      assert.match(await page.evaluate(text('#caption-text')), message, src);
      assert.match(await page.evaluate("document.getElementById('caption').className"), /stopped/, src);
      assert.deepEqual(appProblems(page.problems), [], src);
      await page.close();
    }

    const src = encodeURIComponent('(+ 1 (* 2 3))'); // 2 steps
    for (const [s, shown] of [['abc', 0], ['', 0], ['-4', 0], ['2.9', 2], ['1e3', 1], ['99999', 2]]) {
      const page = await chrome.openPage({ width: 1280, height: 800 });
      await page.navigate(`${url}#e=${src}&s=${s}`);
      await page.waitFor(ready);
      assert.equal(await page.evaluate(text('#counter')), `step ${shown} of 2`, `s=${s}`);
      assert.equal(await page.evaluate("document.getElementById('scrub').value"), String(shown), `s=${s}`);
      assert.deepEqual(appProblems(page.problems), []);
      await page.close();
    }
  });
});

test('phone width (400 px, emulated): no horizontal page scroll at any fib step', { skip, timeout: 120000 }, async () => {
  await withSite(async (url, chrome) => {
    const page = await chrome.openPage({ width: 400, height: 860, mobile: true, scale: 2 });
    await page.navigate(url);
    await page.waitFor(ready);
    assert.equal(await page.evaluate('window.innerWidth'), 400);
    for (let i = 0; i <= 71; i++) {
      const over = await page.evaluate('document.documentElement.scrollWidth - window.innerWidth');
      assert.ok(over <= 0, `step ${i}: page is ${over}px wider than the screen`);
      await page.key('ArrowRight');
    }
    assert.deepEqual(appProblems(page.problems), []);
    await page.close();
  });
});

// WCAG AA: body and caption text at least 4.5:1 against what is behind it.
const CONTRAST = `(() => {
  const parse = (c) => (c.match(/[\\d.]+/g) || []).map(Number);
  const lum = ([r, g, b]) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const bgOf = (el) => {
    for (let e = el; e; e = e.parentElement) {
      const c = parse(getComputedStyle(e).backgroundColor);
      if (c.length === 3 || (c.length === 4 && c[3] > 0.99)) return c.slice(0, 3);
    }
    return [255, 255, 255];
  };
  const ratio = (el) => {
    const a = lum(parse(getComputedStyle(el).color).slice(0, 3)), b = lum(bgOf(el));
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  };
  const sels = ['#caption-text', '.counter', '.label', '.env-hint', '.count', '.colophon p',
                '.icard dd', '.icard-kind', '.icard-note', '.tab-note', '.subset summary',
                '#tree .card.d0 .atom', '#tree .card.d3 .atom', '#tree .card.d4 .atom', '#tree .br',
                '.top > .form > .val', '.preset', '.run'];
  const out = {};
  for (const s of sels) {
    const els = [...document.querySelectorAll(s)];
    if (els.length) out[s] = Math.min(...els.map(ratio));
  }
  return out;
})()`;

for (const scheme of ['light', 'dark']) {
  test(`contrast (${scheme}): text reaches WCAG AA`, { skip, timeout: 60000 }, async () => {
    await withSite(async (url, chrome) => {
      const page = await chrome.openPage({ width: 1280, height: 800, scheme });
      await page.navigate(url);
      await page.waitFor(ready);
      for (let i = 0; i < 20; i++) await page.key('ArrowRight'); // three calls deep
      const ratios = await page.evaluate(CONTRAST);
      console.log(`  ${scheme}:`, Object.entries(ratios).map(([k, v]) => `${k} ${v.toFixed(2)}`).join(', '));
      assert.ok(Object.keys(ratios).length >= 14, 'most elements were found');
      for (const [sel, r] of Object.entries(ratios)) assert.ok(r >= 4.5, `${sel}: ${r.toFixed(2)}:1`);
      assert.deepEqual(appProblems(page.problems), []);
      await page.close();
    });
  });
}

test('prefers-reduced-motion: steps change instantly', { skip, timeout: 60000 }, async () => {
  await withSite(async (url, chrome) => {
    const page = await chrome.openPage({ width: 1280, height: 800, reducedMotion: true });
    await page.navigate(url);
    await page.waitFor(ready);
    await page.key('ArrowRight');
    assert.equal(await page.evaluate("document.querySelectorAll('.ghost').length"), 0);
    assert.equal(await page.evaluate('document.getAnimations().length'), 0);
    await page.close();

    const moving = await chrome.openPage({ width: 1280, height: 800 });
    await moving.navigate(url);
    await moving.waitFor(ready);
    await moving.key('ArrowRight');
    assert.ok(await moving.evaluate('document.getAnimations().length') > 0, 'without the preference, the fold animates');
    await moving.close();
  });
});
