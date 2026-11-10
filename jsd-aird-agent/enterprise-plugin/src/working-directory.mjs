import WorkingDirectoryService from '../../../vendor/deepseek-harness/packages/session/working-directory/lib/index.js';

/** Preserve native directory state and commands; expose the execution world's path. */
export default class EnterpriseWorkingDirectory extends WorkingDirectoryService {
  get(session) {
    return this.ctx.fs.processPath({ targetKey: this.ctx.fs.hostPath(super.get(session)) });
  }
}
