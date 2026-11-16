import { apply as nativeInstructions, Config } from '../../../vendor/deepseek-harness/packages/context/agent-instructions/lib/index.js';

export { Config };
export const name = 'enterprise-instruction-runtime';
export const inject = ['agents', 'workingDirectory', 'fs'];

/** Native discovery uses node:path; keep its private probes in host coordinates. */
export function apply(ctx, config) {
  const directory = {
    async ensure(agent, signal) {
      return ctx.fs.hostPath(await ctx.workingDirectory.ensure(agent, signal));
    },
  };
  nativeInstructions(new Proxy(ctx, { get(target, key) {
    if (key === 'workingDirectory') return directory;
    if (key === 'on') return (event, listener, ...options) => ctx.on(event,
      event !== 'tools/result' ? listener : (execution, result) => {
        const path = execution.arguments?.file_path;
        if (typeof path !== 'string' || !execution.agent || result.isError ||
            !['read', 'write', 'edit'].includes(execution.name)) return listener(execution, result);
        // Retain the native touch/reconciliation lifecycle and owner boundary.
        const cwd = ctx.workingDirectory.get(execution.agent?.session);
        const hostPath = ctx.fs.hostPath(path, cwd);
        return listener({...execution, arguments:{...execution.arguments, file_path:hostPath}}, result);
      }, ...options);
    return Reflect.get(target, key, target);
  } }), config);
}
