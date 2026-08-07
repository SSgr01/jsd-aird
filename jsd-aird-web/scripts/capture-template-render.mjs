import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 1) {
  const key = process.argv[index];
  if (key?.startsWith('--')) args.set(key.slice(2), process.argv[index + 1] ?? '');
}

const url = args.get('url');
const output = args.get('output');
const timeoutMs = Number(args.get('timeout-ms') || 20000);
if (!url || !output) {
  throw new Error('用法：capture-template-render.mjs --url <url> --output <png> [--timeout-ms <ms>]');
}

const browserPath = process.env.JSD_AIRD_RENDER_BROWSER_PATH || findBrowser();
const browser = await chromium.launch({
  headless: true,
  ...(browserPath ? { executablePath: browserPath } : {}),
});

try {
  const page = await browser.newPage({
    viewport: { width: 1920, height: 1200 },
    deviceScaleFactor: 1,
  });
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
  await page.waitForFunction(
    () => window.templateRenderReady === true,
    undefined,
    { timeout: timeoutMs },
  );
  const metadata = await page.evaluate(() => ({
    width: document.documentElement.scrollWidth,
    height: document.documentElement.scrollHeight,
    ready: document.querySelector('[data-template-render-ready]')?.getAttribute('data-template-render-ready') === 'true',
    rangeRectAvailable: typeof window.getSpreadsheetRangeRect === 'function',
  }));
  await page.screenshot({ path: resolve(output), fullPage: true, type: 'png' });
  process.stdout.write(JSON.stringify({ status: 'RENDERED', url, ...metadata }));
} finally {
  await browser.close();
}

function findBrowser() {
  const programFiles = process.env.ProgramFiles || 'C:\\Program Files';
  const programFilesX86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const candidates =
    process.platform === 'win32'
      ? [
          programFiles + '\\Google\\Chrome\\Application\\chrome.exe',
          programFilesX86 + '\\Google\\Chrome\\Application\\chrome.exe',
          programFilesX86 + '\\Microsoft\\Edge\\Application\\msedge.exe',
          programFiles + '\\Microsoft\\Edge\\Application\\msedge.exe',
        ]
      : ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  return candidates.find((candidate) => existsSync(candidate));
}
