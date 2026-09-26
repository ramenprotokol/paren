// Minimal Chrome DevTools Protocol driver, used by the browser test and the
// screenshot script. Launches headless Chrome with a random debugging port,
// opens pages with exact device emulation (a real headless window will not
// go below 500 px wide), and records console errors, uncaught exceptions and
// failed requests.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export function findChrome(env = process.env) {
  if (env.CHROME_PATH) return existsSync(env.CHROME_PATH) ? env.CHROME_PATH : null;
  const candidates = [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ];
  return candidates.find((p) => existsSync(p)) ?? null;
}

export async function launchChrome(chromePath) {
  const profile = mkdtempSync(join(tmpdir(), 'paren-chrome-'));
  const proc = spawn(
    chromePath,
    [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-extensions',
      '--hide-scrollbars',
      '--force-color-profile=srgb',
      'about:blank',
    ],
    { stdio: ['ignore', 'ignore', 'pipe'] },
  );
  const wsUrl = await new Promise((resolve, reject) => {
    let buf = '';
    const timer = setTimeout(() => reject(new Error('Chrome did not start within 20 s')), 20000);
    proc.stderr.on('data', (d) => {
      buf += d;
      const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf);
      if (m) {
        clearTimeout(timer);
        resolve(m[1]);
      }
    });
    proc.once('exit', (code) => reject(new Error(`Chrome exited early (${code})`)));
  });

  const ws = new WebSocket(wsUrl);
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });
  let nextId = 0;
  const pending = new Map();
  const listeners = new Set();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const p = pending.get(msg.id);
      pending.delete(msg.id);
      if (msg.error) p.reject(new Error(msg.error.message));
      else p.resolve(msg.result);
    } else {
      for (const l of listeners) l(msg);
    }
  };
  const send = (method, params = {}, sessionId) => {
    const id = ++nextId;
    ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
  };

  async function openPage({ width, height, mobile = false, scale = 1, scheme, reducedMotion }) {
    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    const s = (method, params) => send(method, params, sessionId);
    const problems = [];
    const onEvent = (msg) => {
      if (msg.sessionId !== sessionId) return;
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails;
        problems.push({ kind: 'exception', text: d.exception?.description ?? d.text });
      } else if (msg.method === 'Runtime.consoleAPICalled' && (msg.params.type === 'error' || msg.params.type === 'warning')) {
        problems.push({ kind: `console.${msg.params.type}`, text: msg.params.args.map((a) => a.value ?? a.description).join(' ') });
      } else if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
        problems.push({ kind: 'log', text: msg.params.entry.text, url: msg.params.entry.url ?? '' });
      }
    };
    listeners.add(onEvent);
    await s('Page.enable');
    await s('Runtime.enable');
    await s('Log.enable');
    await s('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
    const features = [];
    if (scheme) features.push({ name: 'prefers-color-scheme', value: scheme });
    if (reducedMotion) features.push({ name: 'prefers-reduced-motion', value: 'reduce' });
    if (features.length) await s('Emulation.setEmulatedMedia', { features });

    const evaluate = async (expression) => {
      const r = await s('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text);
      return r.result.value;
    };
    const waitFor = async (expression, timeout = 15000) => {
      const end = Date.now() + timeout;
      while (Date.now() < end) {
        if (await evaluate(expression).catch(() => false)) return true;
        await new Promise((r) => setTimeout(r, 50));
      }
      throw new Error(`timed out waiting for: ${expression}`);
    };
    const key = async (keyName, code = keyName) => {
      const vk = { ArrowRight: 39, ArrowLeft: 37, Home: 36, End: 35, Enter: 13 }[keyName] ?? 0;
      await s('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: keyName, code, windowsVirtualKeyCode: vk });
      await s('Input.dispatchKeyEvent', { type: 'keyUp', key: keyName, code, windowsVirtualKeyCode: vk });
    };
    return {
      problems,
      evaluate,
      waitFor,
      key,
      navigate: (url) => s('Page.navigate', { url }),
      screenshot: async (opts = {}) =>
        Buffer.from((await s('Page.captureScreenshot', { format: 'png', ...opts })).data, 'base64'),
      close: async () => {
        listeners.delete(onEvent);
        await send('Target.closeTarget', { targetId }).catch(() => {});
      },
    };
  }

  async function close() {
    try { ws.close(); } catch { /* already closed */ }
    proc.kill();
    await new Promise((r) => (proc.exitCode !== null ? r() : proc.once('exit', r)));
    rmSync(profile, { recursive: true, force: true });
  }

  return { openPage, close };
}
