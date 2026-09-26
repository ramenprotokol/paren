// Smoke test for the built dist/ folder: it is complete, self-consistent,
// served with the production headers, and within the size budget.
import { test } from 'node:test';
import assert from 'node:assert/strict';
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
    if (/max-age=31536000/.test(b)) assert.match(b.split('\n')[0], /^\/(js|css)\/\*$/, `long cache on ${b.split('\n')[0]}`);
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
  } finally {
    server.close();
  }
});
