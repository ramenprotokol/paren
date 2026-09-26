#!/usr/bin/env node
// Screenshots of dist/ for visual review: desktop light and dark, and a true
// 400 px phone width via device emulation. Writes to SHOTS_DIR (default
// shots/, which is gitignored). Usage: node scripts/shots.mjs [preset] [step]
// With SRC set, the page opens that expression (as a share link would)
// instead of a preset; NAME then labels the files.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { serve } from './serve.mjs';
import { findChrome, launchChrome } from './cdp.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = process.env.SHOTS_DIR ?? join(root, 'shots');
mkdirSync(outDir, { recursive: true });

const chromePath = findChrome();
if (!chromePath) {
  console.error('Chrome not found; set CHROME_PATH');
  process.exit(1);
}
const [presetArg, stepArg] = process.argv.slice(2);
const server = await serve(join(root, 'dist'));
const base = `http://127.0.0.1:${server.address().port}/`;
const chrome = await launchChrome(chromePath);
try {
  const views = [
    { name: 'desktop-light', width: 1280, height: 800, scheme: 'light' },
    { name: 'desktop-dark', width: 1280, height: 800, scheme: 'dark' },
    { name: 'phone-light', width: 400, height: 860, scheme: 'light', mobile: true, scale: 2 },
    { name: 'phone-dark', width: 400, height: 860, scheme: 'dark', mobile: true, scale: 2 },
  ];
  const only = process.env.VIEWS ? process.env.VIEWS.split(',') : null;
  for (const v of views.filter((x) => !only || only.includes(x.name))) {
    const page = await chrome.openPage(v);
    await page.navigate(process.env.SRC ? `${base}#e=${encodeURIComponent(process.env.SRC)}` : base);
    await page.waitFor("document.documentElement.dataset.state === 'ready'");
    await page.evaluate('document.fonts.ready.then(() => true)');
    if (presetArg && presetArg !== '-') {
      await page.evaluate(`document.querySelector('[data-preset="${presetArg}"]').click(), true`);
    }
    if (stepArg) {
      for (let i = 0; i < Number(stepArg); i++) await page.key('ArrowRight');
    }
    await new Promise((r) => setTimeout(r, 700));
    const full = process.env.FULL === '1';
    const png = await page.screenshot(full ? { captureBeyondViewport: true } : {});
    const label = process.env.NAME ?? presetArg;
    const file = join(outDir, `${v.name}${label ? `-${label}` : ''}${stepArg ? `-s${stepArg}` : ''}.png`);
    writeFileSync(file, png);
    const scroll = await page.evaluate('document.documentElement.scrollWidth - window.innerWidth');
    console.log(`${file}  (horizontal overflow: ${scroll}px)  problems: ${JSON.stringify(page.problems)}`);
    await page.close();
  }
} finally {
  await chrome.close();
  server.close();
}
