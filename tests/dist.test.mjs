// Smoke test for the built dist/ folder: it is complete, self-consistent,
// served with the production headers, and within the size budget.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { serve } from '../scripts/serve.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const read = (p) => readFileSync(join(dist, p));

test('dist/ holds a complete, hashed static site', () => {
  assert.ok(existsSync(join(dist, 'index.html')), 'run `npm run build` first');
  const html = read('index.html').toString();
  const js = /src="(js\/main\.[0-9A-F]{8}\.js)"/.exec(html)?.[1];
  const css = /href="(css\/styles\.[0-9a-f]{8}\.css)"/.exec(html)?.[1];
  assert.ok(js, 'index.html references a content-hashed JS bundle');
  assert.ok(css, 'index.html references a content-hashed stylesheet');
  assert.ok(existsSync(join(dist, js)));
  assert.ok(existsSync(join(dist, css)));
  assert.ok(!html.includes('%JS%') && !html.includes('%CSS%'));
  assert.ok(existsSync(join(dist, 'favicon.svg')));
  const all = readdirSync(dist, { recursive: true }).map(String);
  assert.ok(!all.some((f) => f.endsWith('.map')), 'no source maps shipped');
  assert.ok(all.length < 20000, 'well under the Pages file limit');
});

// The self-hosted fonts, by source name (public/fonts/<name>.woff2).
const FONTS = ['ibm-plex-mono-400', 'ibm-plex-mono-500', 'ibm-plex-mono-600', 'ibm-plex-mono-italic-400',
               'instrument-serif-400', 'instrument-serif-italic-400'];

test('fonts ship from this site: no Google Fonts in dist/, hashed WOFF2 files the stylesheet uses', () => {
  const html = read('index.html').toString();
  const cssPath = /href="(css\/styles\.[0-9a-f]{8}\.css)"/.exec(html)[1];
  const css = read(cssPath).toString();
  const headers = read('_headers').toString();
  for (const [name, text] of [['index.html', html], ['the stylesheet', css], ['_headers', headers]]) {
    assert.doesNotMatch(text, /googleapis|gstatic|fonts\.google/, `${name} must not reach Google`);
  }
  assert.match(headers, /Content-Security-Policy: [^\n]*; style-src 'self'; font-src 'self';/);
  const files = existsSync(join(dist, 'fonts')) ? readdirSync(join(dist, 'fonts')).sort() : [];
  assert.deepEqual(files.map((f) => f.replace(/\.[0-9a-f]{8}\.woff2$/, '')), FONTS, 'six content-hashed WOFF2 files in dist/fonts/');
  for (const f of files) {
    const bytes = read(`fonts/${f}`);
    assert.equal(bytes.subarray(0, 4).toString('latin1'), 'wOF2', `${f} is WOFF2`);
    assert.equal(f.split('.')[1], createHash('sha256').update(bytes).digest('hex').slice(0, 8), `${f}: the name carries its content hash`);
    assert.ok(css.includes(`url("../fonts/${f}")`), `${f} is referenced from the stylesheet`);
  }
  const faces = css.match(/@font-face\s*\{[^}]*\}/g) ?? [];
  assert.equal(faces.length, FONTS.length, 'one @font-face per file');
  for (const face of faces) assert.match(face, /font-display: swap;/);
  for (const [, url] of css.matchAll(/url\("([^"]+)"\)/g)) {
    if (!url.startsWith('data:')) assert.ok(existsSync(join(dist, dirname(cssPath), url)), `${url} (from the stylesheet) exists`);
  }
});

test('dist/ ships third-party notices for the compiled bundle', () => {
  assert.ok(existsSync(join(dist, 'THIRD-PARTY-NOTICES.txt')), 'THIRD-PARTY-NOTICES.txt is missing from dist/');
  const t = read('THIRD-PARTY-NOTICES.txt').toString();
  const html = read('index.html').toString();
  const js = /src="(js\/[^"]+)"/.exec(html)[1];
  assert.ok(t.includes(js), 'names the bundle it describes');
  // Each component with a version, its copyright line, licence and source.
  for (const re of [
    /ClojureScript \d+\.\d+\.\d+/, /Copyright \(c\) Rich Hickey/, /github\.com\/clojure\/clojurescript/,
    /tools\.reader \d+\.\d+\.\d+/, /Copyright \(c\) Nicola Mometto, Rich Hickey & contributors/, /github\.com\/clojure\/tools\.reader/,
    /shadow-cljs \d+\.\d+\.\d+/, /Thomas Heller/, /github\.com\/thheller\/shadow-cljs/,
    /Google Closure Library 0\.0-\d{8}-[0-9a-f]+/, /Copyright The Closure Library Authors/, /github\.com\/google\/closure-library/,
  ]) assert.match(t, re);
  // EPL-1.0 section 3 object-code terms, and the full licence texts.
  assert.match(t, /On behalf of all Contributors, all warranties and conditions/);
  assert.match(t, /On behalf of all Contributors, all liability for damages is excluded/);
  assert.match(t, /offered by\s+Ramen Protocol alone and not by any other party/);
  assert.match(t, /Source code for these components is available/);
  assert.match(t, /Eclipse Public License - v 1\.0[\s\S]*THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS[\s\S]*State of New York/);
  assert.match(t, /Apache License\s+Version 2\.0, January 2004[\s\S]*END OF TERMS AND CONDITIONS/);
  assert.match(t, /MIT License\s+Copyright \(c\) 2026 ramenprotokol/);
  // The self-hosted fonts: every file, its copyright line (and reserved name), and the OFL text once.
  const fonts = existsSync(join(dist, 'fonts')) ? readdirSync(join(dist, 'fonts')) : [];
  assert.equal(fonts.length, FONTS.length, 'the font files are in dist/fonts/');
  for (const f of fonts) assert.ok(t.includes(`fonts/${f}`), `${f} is listed`);
  assert.match(t, /IBM Plex Mono 2\.005/);
  assert.match(t, /Copyright © 2017 IBM Corp\. with Reserved Font Name "Plex"/);
  assert.match(t, /Instrument Serif 1\.000/);
  assert.match(t, /Copyright 2022 The Instrument Serif Project Authors \(https:\/\/github\.com\/Instrument\/instrument-serif\)/);
  assert.match(t, /github\.com\/IBM\/plex/);
  assert.match(t, /SIL OPEN FONT LICENSE Version 1\.1 - 26 February 2007[\s\S]*PERMISSION & CONDITIONS[\s\S]*TERMINATION/);
  assert.equal(t.match(/PERMISSION & CONDITIONS/g).length, 1, 'the OFL text appears once');
  assert.doesNotMatch(t, /not part of this site|asks Google Fonts/);
  assert.doesNotMatch(t, /\/Users\/|\/home\//, 'no local paths');
  // The page and the bundle both point at it.
  assert.match(html, /<a href="THIRD-PARTY-NOTICES\.txt">third-party notices<\/a>/);
  assert.match(read(js).toString().slice(0, 600), /\/\*! paren \(MIT\)[^*]*EPL-1\.0[^*]*THIRD-PARTY-NOTICES\.txt \*\//);
});

test('_headers: strict CSP, long cache only on hashed files', () => {
  const h = read('_headers').toString();
  assert.match(h, /Content-Security-Policy: default-src 'self'; script-src 'self';/);
  assert.doesNotMatch(h, /unsafe-(inline|eval)/);
  const blocks = h.split(/\n(?=\S)/);
  for (const b of blocks) {
    if (/max-age=31536000/.test(b)) assert.match(b.split('\n')[0], /^\/(js|css|fonts)\/\*$/, `long cache on ${b.split('\n')[0]}`);
  }
  assert.match(h, /\/index\.html\n\s+Cache-Control: no-cache/);
});

test('the JS bundle stays small (gzip, measured)', () => {
  const html = read('index.html').toString();
  const js = /src="(js\/[^"]+)"/.exec(html)[1];
  const gz = gzipSync(read(js), { level: 9 }).length;
  console.log(`  ${js}: ${(read(js).length / 1024).toFixed(1)} KiB raw, ${(gz / 1024).toFixed(1)} KiB gzip`);
  assert.ok(gz < 100 * 1024, `bundle is ${gz} bytes gzipped; budget is 100 KiB`);
});

test('dist/ serves with the production headers', async () => {
  const server = await serve(dist);
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    const res = await fetch(`${base}/`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-security-policy') ?? '', /script-src 'self'/);
    const html = await res.text();
    const js = /src="(js\/[^"]+)"/.exec(html)[1];
    const jr = await fetch(`${base}/${js}`);
    assert.equal(jr.status, 200);
    assert.match(jr.headers.get('content-type'), /javascript/);
    assert.ok((await jr.text()).length > 1000);
    const nr = await fetch(`${base}/THIRD-PARTY-NOTICES.txt`);
    assert.equal(nr.status, 200);
    assert.match(nr.headers.get('content-type'), /text\/plain/);
    const font = readdirSync(join(dist, 'fonts'))[0];
    const fr = await fetch(`${base}/fonts/${font}`);
    assert.equal(fr.status, 200);
    assert.equal(fr.headers.get('content-type'), 'font/woff2');
  } finally {
    server.close();
  }
});
