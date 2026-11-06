import { timingSafeEqual } from 'node:crypto';

export function credentialMatches(actual, secret) {
  const expected = Buffer.from('Bearer ' + secret);
  const provided = Buffer.from(actual || '');
  return expected.length === provided.length && timingSafeEqual(expected, provided);
}
export function ownerKey(identity) {
  if (![identity.organization_id, identity.actor_id].every(v => /^[0-9a-f-]{36}$/i.test(v))) {
    throw Object.assign(new Error('INVALID_IDENTITY'), { status: 401 });
  }
  return `${identity.organization_id}/${identity.actor_id}`;
}
export function sameOwner(a, b) {
  return a.organization_id === b.organization_id && a.actor_id === b.actor_id && a.auth_version === b.auth_version;
}
export function requireSameOrigin(request, origin) {
  const supplied = request.headers.origin;
  if (supplied && supplied !== origin) throw Object.assign(new Error('ORIGIN_DENIED'), { status: 403 });
  if (request.headers['sec-fetch-site'] === 'cross-site') throw Object.assign(new Error('ORIGIN_DENIED'), { status: 403 });
  if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method) && supplied !== origin) {
    throw Object.assign(new Error('ORIGIN_REQUIRED'), { status: 403 });
  }
}
export async function readJson(request, limit = 20 * 1024 * 1024) {
  const chunks = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) throw Object.assign(new Error('PAYLOAD_TOO_LARGE'), { status: 413 });
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString() || '{}');
}
export function json(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  response.end(JSON.stringify(body));
}
