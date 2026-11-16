import {mkdir} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';

/** Give a legacy scheduled session a task directory once; retain its old files in place. */
export async function ensureTaskDirectory(ctx,agent,signal,files=[]) {
  if(ctx.workingDirectory.taskRoot(agent.session))return ctx.workingDirectory.ensure(agent,signal);
  if(!/^[A-Za-z0-9_-]{1,200}$/.test(agent.id))throw new Error('TASK_DIRECTORY_ID_INVALID');
  const directory=`/workspaces/tasks/${agent.id}/${randomUUID()}`;
  const host=await ctx.fs.checked(directory);
  signal?.throwIfAborted();
  await mkdir(host,{recursive:true});
  await ctx.fs.checked(directory);
  for(const file of files) {
    signal?.throwIfAborted();
    const target=await ctx.fs.resolve(file.path,{cwd:directory});
    await ctx.fs.publish(String(target.targetKey),Buffer.from(file.base64,'base64'));
  }
  await ctx.workingDirectory.set(agent,directory,signal);
  return directory;
}

/** Create an independent working copy before a new native Agent is published.
 * Workspace membership and the original header stay owned by the controller.
 * Restores and subsequent turns keep the directory recorded in native events.
 */
export function installTaskDirectories(ctx) {
  ctx.on('agent/created', async ({agent,source,signal}) => {
    if (source !== 'startup') return;
    const fs=ctx.fs;
    // A native fork inherits history and receives its own copy of the current
    // files. Ordinary new tasks start empty, even in a populated Workspace.
    const sourceDirectory=ctx.workingDirectory.get(agent.session);
    const files=agent.session.inheritedEventCount > 0
      ? await fs.snapshot(sourceDirectory,signal,{excludeProcessOutput:true}) : [];
    await ensureTaskDirectory(ctx,agent,signal,files);
  });
}
