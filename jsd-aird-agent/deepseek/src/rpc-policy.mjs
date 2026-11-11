import { executionDirectory } from './paths.mjs';
import { profileForPreset } from './session-policy.mjs';
import { checkScheduleRequest, scheduleMethods } from './schedule-policy.mjs';

const sessionMethods = new Set(['list', 'search', 'create', 'fork', 'prompt', 'updateQueue', 'cancel', 'rename',
  'state', 'wait', 'follow', 'page', 'projections', 'attachment', 'observeEvent', 'observeEvents', 'observe',
  'history', 'models', 'modelCatalog', 'selectModel', 'listSkills', 'skillCatalog']);
const readNamespaces = new Set(['remoteAccess', 'sessionStats', 'sessionTitles', 'sessionTurnOutline',
  'agentPresets', 'permissionPresets', 'planMode', 'userQuestions', 'approvals', 'messageFeedback']);
const workspaceMethods = new Set(['baseline', 'initializeDefault', 'create', 'rename', 'delete', 'insertBefore',
  'insertSessionBefore', 'archiveSession', 'unarchiveSession', 'pinSession', 'unpinSession']);
const archiveMethods = new Set(['deleteSession','deleteArchivedSessions','unarchiveSession','archivedSessionMetadata',
  'favoriteSessions','setSessionFavorite','searchSessionContent','searchArchivedContent','previewArchivedSession',
  'diagnoseSessionCaches','rebuildSessionCaches','diagnoseOrphanSubagents']);
function deny(message = 'RPC_DENIED') { throw Object.assign(new Error(message), { status: 403 }); }

const preferenceFields = {
  'ui-theme': {
    preference: value => ['light','dark','system'].includes(value),
    fontSize: value => Number.isInteger(value) && value >= 10 && value <= 22,
    codeFontSize: value => Number.isInteger(value) && value >= 10 && value <= 16,
    terminalFontSize: value => Number.isInteger(value) && value >= 10 && value <= 20,
    textFontFamily: value => typeof value === 'string' && value.length <= 1000,
    codeFontFamily: value => typeof value === 'string' && value.length <= 1000,
    terminalFontFamily: value => typeof value === 'string' && value.length <= 1000,
  },
  'ui-chat': {
    transcriptView: value => ['compact','standard','detailed','verbose'].includes(value),
    collapseTiming: value => ['completion','next-input'].includes(value),
    foldedReplies: value => ['all','final'].includes(value),
    performanceUsage: value => ['compact','detailed'].includes(value),
    linkOpening: value => ['sidebar','new-tab'].includes(value),
  },
  'ui-settings-general': { welcomeNoticeVersion: value => typeof value === 'string' && value.length <= 80 },
  'ui-settings': { enabled: value => typeof value === 'boolean' },
  'dsh-rewind-plugin': {
    fileCheckpointingEnabled: value => typeof value === 'boolean',
    autoCleanupEnabled: value => typeof value === 'boolean',
    autoCleanupMaxAgeDays: value => Number.isInteger(value) && value >= 1 && value <= 3650,
  },
};
function checkPreferences(method, args) {
  const fields = preferenceFields[args.ns];
  const edits = method === 'mutate' ? args.ops?.map(op => {
    if (op.op !== 'set' || op.path?.length !== 1) deny();
    return [op.path[0], op.value];
  }) : args.patch && typeof args.patch === 'object' && !Array.isArray(args.patch) ? Object.entries(args.patch) : null;
  if (!edits?.length || edits.some(([key,value]) => !Object.hasOwn(fields,key) || !fields[key](value))) deny();
}

export async function checkRpc(endpoint, envelope, { executionRoot, identity }) {
  const parts = endpoint.split('/');
  if (endpoint === '$events/result') return;
  if (envelope.type !== 'client-request' || envelope.method !== endpoint) throw Object.assign(new Error('RPC_ENVELOPE_INVALID'), {status:400});
  if (parts.length !== 2) deny();
  const [namespace, method] = parts;
  const args = envelope.payload?.args;
  const request = args?.request;
  if (namespace === 'workspaceRegistry' && archiveMethods.has(method)) {
    const input = ['setSessionFavorite','previewArchivedSession'].includes(method) ? args?.input : args;
    if (method === 'setSessionFavorite' && typeof input?.favorite !== 'boolean') deny('FAVORITE_REQUIRED');
    if (['deleteSession','unarchiveSession','setSessionFavorite','previewArchivedSession'].includes(method)
      && (typeof input?.sessionId !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(input.sessionId))) deny('SESSION_REQUIRED');
    return;
  }
  if (namespace === 'schedule' && scheduleMethods.has(method)) {
    await checkScheduleRequest(method, request, executionRoot);
    return;
  }
  if(namespace==='job' && ['list','follow','kill'].includes(method)) {
    nativeFileSession(endpoint,envelope);
    return;
  }
  if (namespace === 'session') {
    if (!sessionMethods.has(method)) deny();
    if (method === 'create') {
      if (!request || typeof request !== 'object' || Array.isArray(request)) deny('SESSION_REQUEST_REQUIRED');
      if (request.workspaceId !== undefined && request.cwd !== undefined) deny('SESSION_LOCATION_CONFLICT');
      profileForPreset(request.agentPreset ?? 'enterprise');
      request.agentPreset ??= 'enterprise';
      if (request.workspaceId !== undefined) {
        if (typeof request.workspaceId !== 'string' || !request.workspaceId) deny('WORKSPACE_REQUIRED');
        // Preserve native membership. The owner-scoped native registry resolves the ID.
      } else request.cwd = await executionDirectory(executionRoot, request.cwd ?? '/workspaces/default');
    }
    if (method === 'prompt' && request?.content?.some(part => !['text','image','file'].includes(part.type) || (part.type === 'file' && (typeof part.receiptId !== 'string' || 'attachment' in part)))) deny('NATIVE_UPLOAD_RECEIPT_REQUIRED');
    return;
  }
  if (namespace === 'agentPresets' && method === 'select') {
    profileForPreset(args?.agentPreset);
    return;
  }
  // Native Models forms and credentials stay in the owner-scoped Host. Only
  // enterprise administrators may change routes or server-held credentials.
  if (namespace === 'settings' && method === 'describe') return;
  const modelAdmin = identity?.role === 'SYSTEM_ADMIN';
  if (namespace === 'llm' && ['listProviders','listConfigurableProviders','listModels'].includes(method)) return;
  if (namespace === 'llm' && method === 'discoverModels' && modelAdmin) return;
  if (namespace === 'credentials' && ['describe','set','unset'].includes(method) && modelAdmin) return;
  if (namespace === 'settings' && ['update','replace','mutate'].includes(method)
      && ['llm-deepseek','llm-pi-ai','ui-settings-models'].includes(args?.ns) && modelAdmin) return;
  if (namespace === 'settings' && ['update','mutate'].includes(method) && Object.hasOwn(preferenceFields,args?.ns)) {
    checkPreferences(method,args);
    return;
  }
  if (namespace === 'settings' && method === 'update' && args?.ns === 'agent-preset-registry') {
    if (!args.patch || Object.keys(args.patch).some(key=>key !== 'selectedDefault')) deny();
    profileForPreset(args.patch.selectedDefault);
    return;
  }
  if (namespace === 'workspaceFiles' && ['read','readBytes','stat','list'].includes(method)) {
    nativeFileSession(endpoint, envelope);
    return;
  }
  if (namespace === 'officeToPdf' && method === 'generation') return;
  if (namespace === 'officeToPdf' && method === 'render') {
    nativeFileSession(endpoint, envelope);
    return;
  }
  if (namespace === 'fileUploads' && ['upload','uploadImage'].includes(method)) return;
  // The native composer loads this catalog before showing file/command entries.
  // Execution resolves only server-composed commands on the receiving Agent.
  if (namespace === 'commands' && ['list', 'execute'].includes(method)) return;
  if (namespace === 'workspace' && workspaceMethods.has(method)) {
    if (method === 'create') {
      if (!request || typeof request.path !== 'string') deny('WORKSPACE_DIRECTORY_REQUIRED');
      request.path = await executionDirectory(executionRoot, request.path);
    }
    return;
  }
  if (namespace === 'directoryPicker' && ['list', 'createDirectory'].includes(method)) {
    if (args?.path !== undefined) await executionDirectory(executionRoot, args.path);
    else if (method === 'createDirectory') deny('DIRECTORY_REQUIRED');
    return;
  }
  if (readNamespaces.has(namespace) && ['list', 'get', 'state', 'follow', 'catalog', 'roster', 'available', 'answer', 'decide'].includes(method)) return;
  throw Object.assign(new Error('RPC_DENIED'), { status: 403 });
}

/** Native file and job controls pass through the same enterprise session ownership gate. */
export function nativeFileSession(endpoint, envelope) {
  if (!endpoint.startsWith('workspaceFiles/') && endpoint !== 'officeToPdf/render' && !endpoint.startsWith('job/')) return;
  const sessionId = endpoint.startsWith('job/') ? envelope.payload?.args?.request?.sessionId : envelope.payload?.args?.workspaceFileScopeId;
  if (typeof sessionId !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(sessionId)) deny('SESSION_REQUIRED');
  return sessionId;
}

const streams = new Set(['$events','session/follow','session/control','remoteAccess/follow','workspace/follow',
  'sessionTitles/follow','sessionStats/follow','sessionTurnOutline/follow','agentPresets/follow',
  'workspaceFiles/changes','permissionPresets/follow','approvals/follow','userQuestions/follow','planMode/follow',
  'job/list','job/follow']);
export function checkStream(frame) {
  if (!['open','item','end','cancel'].includes(frame?.type)) throw new Error('STREAM_DENIED');
  if (frame.type === 'open' && !streams.has(frame.endpoint)) throw new Error('STREAM_DENIED');
}

/** Preserve the native binary protocol; it is never a Typert JSON envelope. */
export function checkUpload(url, headers) {
  const sid = url.searchParams.get('sessionId');
  if (!sid || !/^[A-Za-z0-9_-]{1,200}$/.test(sid)) deny('SESSION_REQUIRED');
  if (headers['content-type']?.split(';')[0] !== 'application/octet-stream')
    throw Object.assign(new Error('UPLOAD_MEDIA_TYPE'), {status:415});
  const name = url.searchParams.get('name') || '';
  if (name.length > 180 || /[\x00-\x1f]/.test(name)) deny('UPLOAD_NAME_INVALID');
  const size = Number(headers['content-length']);
  if (!Number.isSafeInteger(size) || size < 0) throw Object.assign(new Error('UPLOAD_LENGTH_REQUIRED'), {status:411});
  if (size > 20 * 1024 * 1024) throw Object.assign(new Error('UPLOAD_TOO_LARGE'), {status:413});
}
