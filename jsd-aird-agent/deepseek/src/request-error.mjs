import { json } from './security.mjs';

/** Browser entries get a recovery page; RPCs and assets retain machine-readable errors. */
export function requestError(req, res, error, origin) {
  if (res.headersSent) { res.destroy(); return; }
  const status = error.status || 503;
  const url = URL.parse(req.url, origin);
  const authenticationRequired = status === 401 && error.message === 'ENTERPRISE_AUTH_REQUIRED';
  const documentEntry = ['GET', 'HEAD'].includes(req.method)
    && url && /^\/agent\/(?:$|(?:qa|research)\/$)/.test(url.pathname)
    && (req.headers['sec-fetch-dest'] === 'document' || /\btext\/html\b/i.test(req.headers.accept || ''));

  // Never include cookies, tickets, query parameters, or upstream error text in logs.
  const reason = /^[A-Z][A-Z_]{0,63}$/.test(error.message || '') ? error.message : 'UPSTREAM_UNAVAILABLE';
  process.stderr.write(JSON.stringify({ event: 'workbench_request_refused', status, reason, path: url?.pathname }) + '\n');
  if (authenticationRequired && documentEntry) {
    // URL fragments are retained by browser redirects and restored after login.
    url.searchParams.delete('token');
    res.writeHead(303, {
      location: '/login?returnTo=' + encodeURIComponent(url.pathname + url.search),
      'cache-control': 'no-store', 'referrer-policy': 'same-origin',
    });
    res.end();
    return;
  }
  if (documentEntry) {
    const denied = status === 403;
    const title = denied ? '暂时无法访问 AI 工作台' : 'AI 工作台暂时无法打开';
    const description = denied ? '请确认当前账号已获得访问权限，或联系管理员。' : '服务暂时未能连接，请稍后重新打开。';
    res.writeHead(status, {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
      'referrer-policy': 'same-origin',
      'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'self'",
      'x-content-type-options': 'nosniff',
    });
    res.end(req.method === 'HEAD' ? undefined : `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>body{margin:0;background:#fafafa;color:#171717;font:14px/1.7 "Microsoft YaHei UI",sans-serif}main{max-width:560px;margin:18vh auto;padding:24px}p{color:#525252}h1{font-size:24px;font-weight:600;line-height:1.4}nav{display:flex;gap:12px;flex-wrap:wrap;margin-top:24px}a{padding:7px 16px;border:1px solid #d4d4d4;border-radius:6px;color:#171717;text-decoration:none}a:first-child{background:#262626;color:white;border-color:#262626}a:focus-visible{outline:2px solid #525252;outline-offset:3px}</style><main><p>AI 工作台</p><h1>${title}</h1><p>${description}</p><nav aria-label="恢复操作"><a href="">重新打开</a><a href="/">返回系统</a></nav></main></html>`);
    return;
  }
  json(res, status, {
    error: authenticationRequired ? 'ENTERPRISE_AUTH_REQUIRED'
      : status === 403 ? 'ENTERPRISE_ACCESS_DENIED'
        : status < 500 ? 'ENTERPRISE_REQUEST_REFUSED' : 'DEEPSEEK_UNAVAILABLE',
  });
}
