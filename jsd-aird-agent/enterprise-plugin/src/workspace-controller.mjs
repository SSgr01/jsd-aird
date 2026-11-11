import {resolve} from 'node:path';
import WorkspaceController from '../../../vendor/deepseek-harness/packages/api/workspace-controller/lib/index.js';

// Retain native registry and commands, replacing only the desktop default path.
export default class EnterpriseWorkspaceController extends WorkspaceController {
  async initializeDefault(signal) {
    signal.throwIfAborted();
    const workspace = await this.ctx.workspaceRegistry.initializeDefault(async () =>
      resolve(process.env.JSD_DSH_EXECUTION_ROOT, 'default'));
    if (!workspace) return undefined;
    return {workspace: {workspaceId: workspace.id, path: workspace.path,
      title: workspace.title, sessionIds: [...workspace.sessionIds],
      createdAt: workspace.createdAt, updatedAt: workspace.updatedAt}};
  }
}
