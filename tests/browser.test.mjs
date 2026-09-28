// End-to-end check in real headless Chrome against dist/, served with the
// production Content-Security-Policy. It drives the page like a learner
// would and fails on any console error, uncaught exception or CSP report,
// and on any request that leaves the page's origin.
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

    // The fonts come from this site: each of the six faces loads, and no
    // request (the fonts included) leaves the page's origin.
    const faces = ['400 16px "IBM Plex Mono"', '500 16px "IBM Plex Mono"', '600 16px "IBM Plex Mono"',
                   'italic 400 16px "IBM Plex Mono"', '400 16px "Instrument Serif"', 'italic 400 16px "Instrument Serif"'];
    assert.equal(await page.evaluate('document.fonts.size'), faces.length, 'one @font-face per self-hosted file');
    const loaded = await page.evaluate(`Promise.all(${JSON.stringify(faces)}.map((f) => document.fonts.load(f).then((l) => l.length)))`);
    assert.deepEqual(loaded, faces.map(() => 1), 'each face resolves to one loaded file');
    for (const f of faces) assert.equal(await page.evaluate(`document.fonts.check(${JSON.stringify(f)})`), true, f);
    assert.equal(await page.evaluate("[...document.fonts].filter((f) => f.status === 'loaded').length"), faces.length);
    const offsite = await page.evaluate("performance.getEntriesByType('resource').map((e) => e.name).filter((u) => !u.startsWith(location.origin + '/'))");
    assert.deepEqual(offsite, [], 'every request stays on this origin');
    const fontFiles = await page.evaluate("performance.getEntriesByType('resource').map((e) => new URL(e.name).pathname).filter((p) => /^\\/fonts\\/[\\w-]+\\.[0-9a-f]{8}\\.woff2$/.test(p))");
    assert.equal(new Set(fontFiles).size, faces.length, `fonts fetched from /fonts/: ${fontFiles.join(', ')}`);

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

    assert.deepEqual(page.problems, []);
    await page.close();

    // A shared link opens the same expression at the same step.
    const shared = await chrome.openPage({ width: 1280, height: 800 });
    const src = '(map (fn [x] (* x x)) [1 2 3])';
    await shared.navigate(`${url}#e=${encodeURIComponent(src)}&s=3`);
    await shared.waitFor(ready);
    assert.equal(await shared.evaluate("document.getElementById('src').value"), src);
    assert.match(await shared.evaluate(text('#counter')), /^step 3 of \d+$/);
    assert.deepEqual(shared.problems, []);
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
      // A recursive call nested twenty calls deep: drawing it overflowed the stack (blank stage).
      [`(defn f [n] (if (= n 0) 0 ${'(inc '.repeat(20)}(f (dec n))${')'.repeat(20)})) (f 99)`,
        /^Stopped at the nesting cap: this step would nest the expression more than 400 boxes deep/],
      // 300 built-in names inside a 250-name let: every step rechecked each name against every local.
      [`(let [${[...'abcdefghijk'].flatMap((a) => [...'abcdefghijklmnopqrstuvwxyz'].map((b) => a + b)).slice(0, 250)
        .map((n) => `${n} 1`).join(' ')}] [${'+ '.repeat(300)}(reduce + (range 1000)) (reduce + (range 1000)) (reduce + (range 600))])`,
        /^Stopped at the step cap: 5,000 steps ran without finishing/],
    ];
    for (const [src, message] of hostile) {
      const page = await chrome.openPage({ width: 1280, height: 800 });
      await page.navigate(`${url}#e=${encodeURIComponent(src)}&s=99999`);
      await withinMs(5000, page.waitFor(ready, 5000), `loading a link to ${src}`);
      // s=99999 is clamped to the last step: the one that stopped.
      assert.match(await page.evaluate(text('#caption-text')), message, src);
      assert.match(await page.evaluate("document.getElementById('caption').className"), /stopped/, src);
      assert.deepEqual(page.problems, [], src);
      await page.close();
    }

    // Quote marks nest a form without a bracket: 800 `~`s overflowed the stack while parsing
    // (blank page); ~1,600 or more overflowed the reader itself.
    for (const src of ['~'.repeat(1000) + 'x', '~'.repeat(1999) + 'x']) {
      const page = await chrome.openPage({ width: 1280, height: 800 });
      await page.navigate(`${url}#e=${encodeURIComponent(src)}`);
      await withinMs(5000, page.waitFor(ready, 5000), `loading a link to ${src.length} quote marks`);
      assert.match(await page.evaluate(text('#src-error')), /^That expression is nested (\d+ levels|too deeply)/);
      assert.deepEqual(page.problems, []);
      await page.close();
    }

    const src = encodeURIComponent('(+ 1 (* 2 3))'); // 2 steps
    for (const [s, shown] of [['abc', 0], ['', 0], ['-4', 0], ['2.9', 2], ['1e3', 1], ['99999', 2]]) {
      const page = await chrome.openPage({ width: 1280, height: 800 });
      await page.navigate(`${url}#e=${src}&s=${s}`);
      await page.waitFor(ready);
      assert.equal(await page.evaluate(text('#counter')), `step ${shown} of 2`, `s=${s}`);
      assert.equal(await page.evaluate("document.getElementById('scrub').value"), String(shown), `s=${s}`);
      assert.deepEqual(page.problems, []);
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
      const over = await page.evaluate('document.documentElement.scrollWidth - 400');
      assert.ok(over <= 0, `step ${i}: page is ${over}px wider than the screen`);
      await page.key('ArrowRight');
    }
    assert.deepEqual(page.problems, []);
    await page.close();
  });
});

test('phone width: a long unbroken name, string or error never widens the page', { skip, timeout: 120000 }, async () => {
  await withSite(async (url, chrome) => {
    const page = await chrome.openPage({ width: 400, height: 860, mobile: true, scale: 2 });
    await page.navigate(url);
    await page.waitFor(ready);
    const long = 'a'.repeat(90);
    for (const src of [`(str "https://example.com/a/rather/long/path/with/no/spaces/at/all")`,
                       `(let [${long} 1] (+ ${long} 1))`, `(defn ${long} [x] x) (${long} 1)`,
                       `(${long} 1)`, `(+ 1 "${long}")`]) {
      await run(page, src);
      for (let i = 0; i < 6; i++) {
        // Against the 400 px screen, not innerWidth: a mobile browser widens
        // the layout viewport to fit content that overflows.
        const over = await page.evaluate('document.documentElement.scrollWidth - 400');
        assert.ok(over <= 0, `${src.slice(0, 30)}… step ${i}: page is ${over}px wider than the screen`);
        await page.key('ArrowRight');
      }
    }
    assert.deepEqual(page.problems, []);
    await page.close();
  });
});

// WCAG AA: body and caption text at least 4.5:1 against what is behind it.
// (color-mix() computes to color(srgb r g b) with channels from 0 to 1.)
const TEXT = ['#caption-text', '.counter', '.label', '.env-hint', '.count', '.colophon p',
              '.icard dd', '.icard-kind', '.icard-note', '.tab-note', '.subset summary',
              '#tree .card.d0 .atom', '#tree .card.d3 .atom', '#tree .card.d4 .atom', '#tree .br',
              '.top > .form > .val', '.preset', '.run'];
const REDEX_CARD = ['#tree .card.redex .atom', '#tree .card.redex > .ln > .br'];
const CONTRAST = (sels) => `(() => {
  const parse = (c) => {
    const n = (c.match(/[\\d.]+/g) || []).map(Number);
    return c.startsWith('color(srgb') ? n.map((v, i) => (i < 3 ? v * 255 : v)) : n;
  };
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
  const sels = ${JSON.stringify(sels)};
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
      for (let i = 0; i < 20; i++) await page.key('ArrowRight'); // three calls deep; the redex is a name
      const ratios = await page.evaluate(CONTRAST(TEXT));
      await page.key('ArrowRight'); // the redex is a whole card, (< 1 2)
      const card = await page.evaluate(CONTRAST(REDEX_CARD));
      Object.assign(ratios, card);
      console.log(`  ${scheme}:`, Object.entries(ratios).map(([k, v]) => `${k} ${v.toFixed(2)}`).join(', '));
      assert.ok(Object.keys(ratios).length >= 14, 'most elements were found');
      assert.equal(Object.keys(card).length, 2, 'the washed redex card was measured');
      for (const [sel, r] of Object.entries(ratios)) assert.ok(r >= 4.5, `${sel}: ${r.toFixed(2)}:1`);
      assert.deepEqual(page.problems, []);
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
