import {ensureDefaultWorkspace} from './default-workspace.mjs';
import WorkspaceController from '../../../vendor/deepseek-harness/packages/api/workspace-controller/lib/index.js';

// Retain native registry and commands, replacing only the desktop default path.
export default class EnterpriseWorkspaceController extends WorkspaceController {
  async initializeDefault(signal) {
    signal.throwIfAborted();
    const workspace = await ensureDefaultWorkspace(this.ctx.workspaceRegistry,process.env.JSD_DSH_EXECUTION_ROOT);
    if (!workspace) return undefined;
    return {workspace: {workspaceId: workspace.id, path: workspace.path,
      title: workspace.title, sessionIds: [...workspace.sessionIds],
      createdAt: workspace.createdAt, updatedAt: workspace.updatedAt}};
  }
}
