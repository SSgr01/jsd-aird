import { json } from './security.mjs';

/** Only document entry requests may redirect; RPCs and assets must keep their status. */
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
  json(res, status, {
    error: authenticationRequired ? 'ENTERPRISE_AUTH_REQUIRED'
      : status === 403 ? 'ENTERPRISE_ACCESS_DENIED'
        : status < 500 ? 'ENTERPRISE_REQUEST_REFUSED' : 'DEEPSEEK_UNAVAILABLE',
  });
}
