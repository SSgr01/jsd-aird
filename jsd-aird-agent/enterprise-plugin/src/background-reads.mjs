import {relative,isAbsolute,sep} from 'node:path';

export const backgroundReads = new Set(['read','read_image','glob','grep']);
// Scheduled reports have a frozen scope and return their text in the session.
// Interactive planning/question tools can suspend them or invent file work.
export const backgroundNativeTools = new Set([...backgroundReads,'skill']);

export async function confineBackgroundRead(ctx, execution) {
  if (!backgroundReads.has(execution.name)) return;
  const cwd = await ctx.workingDirectory.ensure(execution.agent,execution.signal);
  const root = ctx.workingDirectory.taskRoot(execution.agent.session);
  if (!root) throw new Error('BACKGROUND_TASK_DIRECTORY_REQUIRED');
  const requested = ['read','read_image'].includes(execution.name) ? execution.arguments.file_path : execution.arguments.path || '.';
  if (typeof requested !== 'string') throw new Error('BACKGROUND_FILE_PATH_REQUIRED');
  const target = await ctx.fs.resolve(requested,{cwd,signal:execution.signal});
  const part = relative(ctx.fs.hostPath(root),String(target.targetKey));
  if (isAbsolute(part) || part === '..' || part.startsWith('..'+sep)) throw new Error('BACKGROUND_FILE_OUTSIDE_TASK');
}
