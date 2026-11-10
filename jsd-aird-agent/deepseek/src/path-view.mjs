import {relative, resolve, isAbsolute, sep, basename} from 'node:path';

// The same directory namespace used by the native tools in the Linux sandbox.
export const workspacePath = '/workspaces';
const pathFields = new Set(['path','cwd','home','root','parent','directory','displayPath','absolutePath','workingDirectory','defaultDirectory']);

export function browserPath(root, value) {
  if (typeof value !== 'string' || !isAbsolute(value)) return value;
  const part = relative(resolve(root), resolve(value));
  if (part === '..' || part.startsWith('..'+sep) || isAbsolute(part)) return value;
  return workspacePath + (part ? '/'+part.split(sep).join('/') : '');
}
export function hostDirectory(root, value) {
  if (value === workspacePath) return resolve(root);
  if (typeof value === 'string' && value.startsWith(workspacePath+'/'))
    return resolve(root, value.slice(workspacePath.length+1));
  return value;
}

/** Change native directory metadata, never message text, URLs or file bytes. */
export function browserMetadata(value, root) {
  if (Array.isArray(value)) return value.map(item => browserMetadata(item, root));
  if (!value || typeof value !== 'object') return value;
  const result = Object.fromEntries(Object.entries(value).map(([key, item]) => [key,
    key === 'home' && typeof item === 'string' && isAbsolute(item) ? workspacePath
    : pathFields.has(key) && typeof item === 'string' ? browserPath(root, item) : browserMetadata(item, root)]));
  if (value.path && browserPath(root, value.path) === workspacePath) {
    if ('name' in result) result.name = '/workspaces';
    if (result.workspaceId && (!result.title || result.title === basename(value.path))) result.title = '/workspaces';
  }
  if (value.workspaceId && value.path && browserPath(root, value.path) === workspacePath+'/default'
      && (!result.title || result.title === 'default')) result.title = workspacePath+'/default';
  return result;
}

export async function browserJsonResponse(reply, headers, root) {
  if (!/application\/json/i.test(String(headers['content-type'] || ''))) return;
  const chunks = []; let length = 0;
  for await (const chunk of reply) {
    length += chunk.length;
    if (length > 24*1024*1024) throw new Error('NATIVE_METADATA_TOO_LARGE');
    chunks.push(chunk);
  }
  const body = JSON.stringify(browserMetadata(JSON.parse(Buffer.concat(chunks).toString('utf8')), root));
  delete headers['content-encoding']; delete headers['transfer-encoding'];
  headers['content-length'] = Buffer.byteLength(body);
  return body;
}
