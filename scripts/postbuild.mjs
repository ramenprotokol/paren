#!/usr/bin/env node
// Assembles dist/ after `shadow-cljs release app`:
//   - copies the content-hashed JS bundle,
//   - content-hashes the stylesheet,
//   - fills index.html with both names,
//   - writes Cloudflare Pages _headers (long cache only for hashed files),
//   - prints raw and gzipped sizes, measured here.
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'dist');
const pub = join(root, 'public');
const built = join(root, 'build', 'app', 'js');

const manifest = JSON.parse(readFileSync(join(built, 'manifest.json'), 'utf8'));
const main = manifest.find((m) => m['module-id'] === 'main' || m.name === 'main');
if (!main) throw new Error('main module missing from build/app/js/manifest.json');
const jsName = main['output-name'];

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'js'), { recursive: true });
mkdirSync(join(out, 'css'), { recursive: true });

copyFileSync(join(built, jsName), join(out, 'js', jsName));

const css = readFileSync(join(pub, 'styles.css'));
const cssName = `styles.${createHash('sha256').update(css).digest('hex').slice(0, 8)}.css`;
writeFileSync(join(out, 'css', cssName), css);

const html = readFileSync(join(pub, 'index.html'), 'utf8')
  .replace('%CSS%', `css/${cssName}`)
  .replace('%JS%', `js/${jsName}`);
if (html.includes('%CSS%') || html.includes('%JS%')) throw new Error('index.html placeholders not filled');
writeFileSync(join(out, 'index.html'), html);
copyFileSync(join(pub, 'favicon.svg'), join(out, 'favicon.svg'));

writeFileSync(
  join(out, '_headers'),
  `/*
  Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'
  X-Content-Type-Options: nosniff
  Referrer-Policy: no-referrer
  Permissions-Policy: camera=(), microphone=(), geolocation=()
/
  Cache-Control: no-cache
/index.html
  Cache-Control: no-cache
/js/*
  Cache-Control: public, max-age=31536000, immutable
/css/*
  Cache-Control: public, max-age=31536000, immutable
`,
);

const size = (buf) => ({ raw: buf.length, gzip: gzipSync(buf, { level: 9 }).length });
const files = {
  [`js/${jsName}`]: size(readFileSync(join(out, 'js', jsName))),
  [`css/${cssName}`]: size(css),
  'index.html': size(Buffer.from(html)),
};
const kb = (n) => `${(n / 1024).toFixed(1)} KiB`;
let rawTotal = 0;
let gzTotal = 0;
console.log('dist/ (sizes measured now; gzip level 9):');
for (const [name, s] of Object.entries(files)) {
  rawTotal += s.raw;
  gzTotal += s.gzip;
  console.log(`  ${name.padEnd(28)} ${kb(s.raw).padStart(10)} raw  ${kb(s.gzip).padStart(10)} gzip`);
}
console.log(`  ${'initial load (our files)'.padEnd(28)} ${kb(rawTotal).padStart(10)} raw  ${kb(gzTotal).padStart(10)} gzip`);
mkdirSync(join(root, 'build'), { recursive: true });
writeFileSync(join(root, 'build', 'sizes.json'), JSON.stringify({ files, rawTotal, gzTotal }, null, 2));
