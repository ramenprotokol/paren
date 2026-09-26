#!/usr/bin/env node
// Assembles dist/ after `shadow-cljs release app`:
//   - copies the content-hashed JS bundle,
//   - content-hashes the stylesheet,
//   - fills index.html with both names,
//   - writes Cloudflare Pages _headers (long cache only for hashed files),
//   - writes THIRD-PARTY-NOTICES.txt for the code compiled into the bundle,
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

// ---------------------------------------------------------------------------
// Third-party notices. :advanced compilation strips the licence headers of
// everything compiled into the bundle, so they are restored here, with the
// versions this build actually resolved (from shadow-cljs's classpath cache)
// and a check that every source file in the bundle belongs to a component
// listed below.

const jarVersion = (files, artifact) => {
  const re = new RegExp(`/${artifact.replace('.', '\\.')}-(\\d[^/]*?)(?:-aot)?\\.jar$`);
  const found = files.map((f) => re.exec(f)?.[1]).filter(Boolean);
  if (found.length !== 1) throw new Error(`expected one ${artifact} jar on the classpath, found ${found.length}`);
  return found[0];
};
const classpath = readFileSync(join(root, '.shadow-cljs', 'classpath.edn'), 'utf8');
const cpFiles = [...classpath.matchAll(/"([^"]+\.jar)"/g)].map((m) => m[1]);
const ver = {
  cljs: jarVersion(cpFiles, 'clojurescript'),
  reader: jarVersion(cpFiles, 'tools.reader'),
  closure: jarVersion(cpFiles, 'google-closure-library'),
  shadow: jarVersion(cpFiles, 'shadow-cljs'),
};

const owners = [
  [/^paren\//, 'paren'],
  [/^cljs\/core\.cljs$|^clojure\/(string|walk)\.cljs$/, 'cljs'],
  [/^cljs\/tools\/reader(\.cljs$|\/)/, 'reader'],
  [/^shadow\//, 'shadow'],
  [/^goog\//, 'closure'],
];
const unowned = main.sources.filter((src) => !owners.some(([re]) => re.test(src)));
if (unowned.length) {
  throw new Error(`bundle contains sources not covered by THIRD-PARTY-NOTICES.txt: ${unowned.join(', ')}`);
}

const epl = readFileSync(join(root, 'licenses', 'EPL-1.0.txt'), 'utf8').trimEnd();
const apache = readFileSync(join(root, 'licenses', 'Apache-2.0.txt'), 'utf8').trimEnd();
const mit = readFileSync(join(root, 'LICENSE'), 'utf8').trimEnd();
const rule = '='.repeat(76);
const notices = `THIRD-PARTY NOTICES FOR paren
${rule}

The JavaScript bundle (js/${jsName}) is compiled and minified.
Besides paren's own code, it contains parts of the four components below,
compiled together by the Closure Compiler in :advanced mode, which removes
the original source headers. This file restores their notices. It is
written at build time by scripts/postbuild.mjs, using the versions the
build resolved.

paren's own code (the paren.* namespaces) is under the MIT License, printed
at the end of this file.

1. ClojureScript ${ver.cljs}
   Parts included: cljs.core, clojure.string, clojure.walk (clojure.walk by
   Stuart Sierra).
   Copyright (c) Rich Hickey. All rights reserved.
   The use and distribution terms for this software are covered by the
   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
   Licence: Eclipse Public License 1.0 (EPL-1.0), full text in section A.
   Source: https://github.com/clojure/clojurescript
           Maven Central: org.clojure:clojurescript:${ver.cljs}
           (the jar contains the ClojureScript source files)

2. tools.reader ${ver.reader} (the cljs.tools.reader namespaces)
   Copyright (c) Nicola Mometto, Rich Hickey & contributors.
   The use and distribution terms for this software are covered by the
   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
   Licence: EPL-1.0, full text in section A.
   Source: https://github.com/clojure/tools.reader (tag v${ver.reader})
           Maven Central: org.clojure:tools.reader:${ver.reader}

3. shadow-cljs ${ver.shadow} (a few lines of module runtime it adds to the bundle)
   Copyright (c) 2024 Thomas Heller.
   Distributed under the Eclipse Public License either version 1.0 or (at
   your option) any later version; used here under EPL-1.0.
   Licence: EPL-1.0, full text in section A.
   Source: https://github.com/thheller/shadow-cljs
           Clojars: thheller/shadow-cljs ${ver.shadow}

4. Google Closure Library ${ver.closure}
   Parts included: goog.* modules that ClojureScript uses.
   Copyright The Closure Library Authors.
   SPDX-License-Identifier: Apache-2.0
   Licence: Apache License, Version 2.0, full text in section B.
   Source: https://github.com/google/closure-library
           Maven Central: org.clojure:google-closure-library:${ver.closure}
   Changes: compiled and minified by the Closure Compiler, with unused code
   removed. No NOTICE file is distributed with this version.

Terms for components 1-3 (EPL-1.0), which Ramen Protocol distributes here
in object code form under section 3 of the Eclipse Public License 1.0:

  a) On behalf of all Contributors, all warranties and conditions, express
     and implied, are disclaimed, including warranties or conditions of
     title and non-infringement, and implied warranties or conditions of
     merchantability and fitness for a particular purpose. The components
     are provided on an "AS IS" basis, without warranties or conditions of
     any kind.
  b) On behalf of all Contributors, all liability for damages is excluded,
     including direct, indirect, special, incidental and consequential
     damages, such as lost profits.
  c) Any provisions that differ from the Eclipse Public License 1.0,
     including the MIT License that covers paren's own code, are offered by
     Ramen Protocol alone and not by any other party.
  d) Source code for these components is available from Ramen Protocol in
     the same way this build obtained it: the build uses the unmodified
     upstream source of the exact versions listed above, published at the
     addresses given for each component (the jars on Maven Central and
     Clojars contain the source files).

The Eclipse Public License 1.0 governs your use of components 1-3; nothing
in paren's MIT License changes that.

Fonts: IBM Plex Mono and Instrument Serif are not part of this site. The
page asks Google Fonts to serve them, under the SIL Open Font License 1.1.

${rule}
A. ECLIPSE PUBLIC LICENSE 1.0 (components 1-3)
${rule}

${epl}

${rule}
B. APACHE LICENSE, VERSION 2.0 (component 4)
${rule}

${apache}

${rule}
paren's own code
${rule}

${mit}
`;
writeFileSync(join(out, 'THIRD-PARTY-NOTICES.txt'), notices);

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
