import { symbols } from '../../../vendor/deepseek-harness/vendor/cordis/lib/index.js';
import { lstat, rm } from 'node:fs/promises';
import { resolve, isAbsolute } from 'node:path';

const raw = service => service?.[symbols.original] ?? service;
const validId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,200}$/.test(value);

/** Only enterprise boundaries; the installed plugins own deletion and rewind. */
export function installSessionLifecycle(ctx, {business, drain, clear}) {
  const handles = new Map(), deleting = new Set(), writes = new Map();
  const registry = raw(ctx.agents), cache = raw(ctx.sessionProjectionCache);
  function wrap(service, method, decorate) {
    if (typeof service?.[method] !== 'function') return;
    const descriptor = Object.getOwnPropertyDescriptor(service, method);
    const original = service[method];
    const wrapped = decorate(original);
    Object.defineProperty(service, method, {configurable:true,writable:true,value:wrapped});
    ctx.effect(() => () => {
      if (service[method] !== wrapped) return;
      if (descriptor) Object.defineProperty(service, method, descriptor);
      else delete service[method];
    });
  }
  for (const method of ['create','resume']) wrap(registry, method, original => async function (...args) {
    const id = args[0]?.sessionId ?? args[0]?.resumeSessionId;
    if (deleting.has(id)) throw new Error('会话正在删除，请新建会话。');
    // Preserve Cordis's caller-traced receiver and the native ownership scope.
    const handle = await Reflect.apply(original, this, args);
    handles.set(handle.agent.id, handle);
    if (deleting.has(handle.agent.id)) {
      await handle.dispose();
      throw new Error('会话正在删除，请新建会话。');
    }
    return handle;
  });
  wrap(cache, 'write', original => function (session) {
    if (deleting.has(session.id)) return Promise.resolve();
    const pending = Promise.resolve(Reflect.apply(original, this, [session]));
    const group = writes.get(session.id) ?? new Set();
    writes.set(session.id, group); group.add(pending);
    void pending.finally(() => { group.delete(pending); if (!group.size) writes.delete(session.id); }).catch(() => {});
    return pending;
  });
  ctx.on('agent/pre-step', async ({agent}, next) => {
    if (deleting.has(agent.id)) throw new Error('会话正在删除，请新建会话。');
    return next();
  });
  ctx.on('enterprise/session-before-delete', async sessionId => {
    if (!validId(sessionId)) throw new Error('SESSION_REQUIRED');
    const tasks = ctx.schedule ? await ctx.schedule.catalog() : [];
    if (tasks.some(task => task.target?.kind === 'session' && task.target.sessionId === sessionId))
      throw new Error('此会话仍关联自动化任务，请先删除对应自动化任务。');
    const agent = ctx.agents.get(sessionId), handle = handles.get(sessionId);
    if (agent && handle?.agent !== agent) throw new Error('会话运行实例尚未释放，请停止后重试。');
    // Register a durable fence before cancelling: a late title/event cannot
    // re-create a deleted business projection after a restart.
    await business('session-delete', {session_id:sessionId,phase:'prepare'});
    deleting.add(sessionId);
    if (handle) { await handle.dispose(); handles.delete(sessionId); }
    await drain(sessionId);
    await Promise.all(writes.get(sessionId) ?? []);
  });
  ctx.on('enterprise/session-deleted', async sessionId => {
    if (!validId(sessionId)) throw new Error('SESSION_REQUIRED');
    // Snapshot bytes belong to this exact plugin session, never to its
    // workspace or the shared content-addressed attachment directory.
    const home = process.env.DSH_HOME;
    if (home && isAbsolute(home)) {
      const root = resolve(home, 'rewind-snapshots'), directory = resolve(root, sessionId);
      for (const path of [root, directory]) {
        const info = await lstat(path).catch(error => { if (error.code !== 'ENOENT') throw error; });
        if (info && (!info.isDirectory() || info.isSymbolicLink())) throw new Error('SNAPSHOT_DIRECTORY_REFUSED');
      }
      await rm(directory, {recursive:true,force:true});
    }
    await business('session-delete', {session_id:sessionId,phase:'commit'});
    clear(sessionId);
  });
  ctx.on('enterprise/session-before-rewind', async ({sessionId}) => {
    if (!validId(sessionId) || deleting.has(sessionId)) throw new Error('SESSION_NOT_AVAILABLE');
    await business('ownership', {session_id:sessionId,profile:'agent'});
    await drain(sessionId);
  });
  return {deleting};
}
