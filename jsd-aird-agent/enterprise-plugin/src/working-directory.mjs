import WorkingDirectoryService from '../../../vendor/deepseek-harness/packages/session/working-directory/lib/index.js';

/** Preserve native directory state and commands; expose the execution world's path. */
export default class EnterpriseWorkingDirectory extends WorkingDirectoryService {
  get(session) {
    return this.ctx.fs.processPath({ targetKey: this.ctx.fs.hostPath(super.get(session)) });
  }

  taskRoot(session) {
    const prefix=`/workspaces/tasks/${session.id}/`;
    for(const event of session.snapshotEvents()) {
      if(event.seq < session.inheritedEventCount || event.type !== 'working-directory/change') continue;
      const cwd=this.ctx.fs.processPath({targetKey:this.ctx.fs.hostPath(event.data.cwd)});
      if(cwd.startsWith(prefix))return prefix+cwd.slice(prefix.length).split('/')[0];
    }
  }

  async set(agent,path,signal) {
    const root=this.taskRoot(agent.session);
    if(root) {
      const target=await this.ctx.fs.resolve(path,{cwd:this.get(agent.session),signal});
      const directory=this.ctx.fs.processPath(target);
      if(directory!==root && !directory.startsWith(root+'/'))throw new Error('TASK_DIRECTORY_OUTSIDE_SESSION');
      path=directory;
    }
    return super.set(agent,path,signal);
  }

  ensure(agent,signal) {
    const root=this.taskRoot(agent.session);
    if(!root)return super.ensure(agent,signal);
    // Native fallback uses header.cwd (the parent Workspace). An unavailable
    // task must never silently resume execution among another task's scripts.
    return this.serialize(agent,signal,async control=>{
      const current=this.get(agent.session);
      if(current!==root && !current.startsWith(root+'/'))throw new Error('TASK_DIRECTORY_OUTSIDE_SESSION');
      for(const directory of current===root?[root]:[current,root]) {
        const target=await this.ctx.fs.resolve(directory,{signal:control});
        const info=await this.ctx.fs.stat(target,control);
        control.throwIfAborted();agent.ctx.fiber.assertActive();
        if(info?.type==='directory') {
          if(directory!==current)this.commit(agent,directory,true,control);
          return directory;
        }
      }
      throw new Error('TASK_DIRECTORY_UNAVAILABLE: 当前任务目录不存在，请恢复任务文件后继续。');
    });
  }
}
