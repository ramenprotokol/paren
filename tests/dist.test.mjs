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
  } finally {
    server.close();
  }
});
