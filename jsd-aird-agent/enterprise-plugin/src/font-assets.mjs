import { readFile } from 'node:fs/promises';

/** Serve only the packaged interface font, never workspace or host files. */
export async function serveFontAsset(req, res) {
  const pathname = new URL(req.url, 'http://local').pathname;
  const file = pathname.startsWith('/font-assets/') ? pathname.slice('/font-assets/'.length) : '';
  const type = file === 'index.css' ? 'text/css; charset=utf-8'
    : file === 'LICENSE' ? 'text/plain; charset=utf-8'
    : /^files\/noto-sans-sc-[a-z0-9-]+-wght-normal\.woff2$/.test(file) ? 'font/woff2' : undefined;
  if (!type) { res.writeHead(404); res.end(); return; }
  if (!['GET', 'HEAD'].includes(req.method)) {
    res.writeHead(405, {allow:'GET, HEAD'}); res.end(); return;
  }
  try {
    const data = await readFile(new URL('../lib/fonts/' + file, import.meta.url));
    res.writeHead(200, {'content-type':type, 'content-length':data.length,
      'x-content-type-options':'nosniff', 'cache-control':'no-cache'});
    res.end(req.method === 'HEAD' ? undefined : data);
  } catch {
    res.writeHead(503); res.end('Interface font unavailable');
  }
}
