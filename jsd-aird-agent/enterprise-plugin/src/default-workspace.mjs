import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {executionDirectory} from '../../deepseek/src/paths.mjs';

/** One enterprise default directory, shared by ordinary and expert sessions. */
export async function ensureDefaultWorkspace(registry, executionRoot) {
  // Validate the parent before creating a child; directory admission rejects links.
  await executionDirectory(executionRoot, executionRoot);
  await mkdir(resolve(executionRoot,'default'),{recursive:true});
  const directory=await executionDirectory(executionRoot,'/workspaces/default');
  const workspace=await registry.create(directory,'默认工作区');
  if (['default','/workspaces/default',directory].includes(workspace.title))
    await workspace.setTitle('默认工作区');
  return workspace;
}
