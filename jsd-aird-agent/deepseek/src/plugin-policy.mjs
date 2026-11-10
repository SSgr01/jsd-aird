const archivePrefix = '/api/michengai/dsh-archive-manager/';
const rewindPaths = new Set(['/api/dsh-rewind/preview','/api/dsh-rewind/candidates']);
const refuse = () => { throw Object.assign(new Error('PLUGIN_REQUEST_DENIED'), {status:403}); };

/** Plugin Fetch routes carry plain JSON, not native Typert envelopes. */
export function pluginRoute(path) {
  return path.startsWith(archivePrefix) || path.startsWith('/api/dsh-rewind/');
}
export function checkPluginRequest(path, method, body) {
  if (rewindPaths.has(path)) {
    if (method !== 'POST' || !body || typeof body.sessionId !== 'string'
      || !/^[A-Za-z0-9_-]{1,200}$/.test(body.sessionId)
      || Object.keys(body).some(key => !['sessionId','seq'].includes(key))
      || path.endsWith('/preview') && (!Number.isSafeInteger(body.seq) || body.seq < 0)) refuse();
    return body.sessionId;
  }
  if (!path.startsWith(archivePrefix)) refuse();
  const setting = path.slice(archivePrefix.length);
  if (!['sidebar-entry','sidebar-favorite-star','online-update'].includes(setting)) refuse();
  if (method === 'GET' || method === 'HEAD') return;
  if (method !== 'POST' || setting === 'online-update' || !body) refuse();
  const field = setting === 'sidebar-entry' ? 'location' : 'enabled';
  if (Object.keys(body).length !== 1 || !Object.hasOwn(body, field)) refuse();
  if (field === 'location' ? !['both','sidebar','settings'].includes(body[field]) : typeof body[field] !== 'boolean') refuse();
}
