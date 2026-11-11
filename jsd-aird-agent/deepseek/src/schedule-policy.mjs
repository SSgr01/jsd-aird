import { executionDirectory } from './paths.mjs';
import { profileForPreset } from './session-policy.mjs';

export const scheduleMethods = new Set(['deliveryLimits', 'create', 'list', 'catalog', 'view', 'history', 'run', 'deleteInactive', 'edit']);

function deny() { throw Object.assign(new Error('SCHEDULE_TARGET_DENIED'), {status:403}); }

/** Keep native timing, storage and editing; admit only managed delivery targets. */
export async function checkScheduleRequest(method, request, executionRoot) {
  if (method !== 'create' && method !== 'edit') return;
  if (!request || typeof request !== 'object' || Array.isArray(request)) deny();
  const container = method === 'create' ? request : request.action === 'update' ? request.patch : undefined;
  if (!container) return; // Native service validates edit actions and patches.
  let target = container.target;
  if (target === undefined && method === 'edit') return;
  target ??= {kind:'new_once'};
  if (!target || typeof target !== 'object' || Array.isArray(target)) deny();
  if (target.kind === 'session') {
    if (Object.keys(target).some(key => !['kind','sessionId'].includes(key))
        || typeof target.sessionId !== 'string' || !/^[A-Za-z0-9_-]{1,200}$/.test(target.sessionId)) deny();
    // The native owner-scoped registry validates membership and archive state.
  } else if (['new','new_once'].includes(target.kind)) {
    if (Object.keys(target).some(key => !['kind','create'].includes(key))) deny();
    const create = target.create ?? {};
    if (!create || typeof create !== 'object' || Array.isArray(create)
        || Object.keys(create).some(key => !['cwd','agentPreset'].includes(key))) deny();
    profileForPreset(create.agentPreset ?? 'enterprise');
    target.create = {...create, agentPreset:create.agentPreset ?? 'enterprise',
      cwd:await executionDirectory(executionRoot, create.cwd ?? executionRoot)};
  } else deny();
  container.target = target;
}
