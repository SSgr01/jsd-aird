import {apply as installReminder} from '../../../vendor/deepseek-harness/packages/guard/repeat-tool-reminder/lib/index.js';
export {Config} from '../../../vendor/deepseek-harness/packages/guard/repeat-tool-reminder/lib/index.js';

export const name = 'enterprise-read-progress-reminder';

/** Keep the native detector, but a committed next-page cursor is a new operation. */
export function apply(ctx, config) {
  installReminder({on(event, listener, ...rest) {
    if (event !== 'tools/post-execute') return ctx.on(event, listener, ...rest);
    return ctx.on(event, (exec, result, next) => {
      const cursor = !result.isError && result.value?.briefing_progress?.cursor;
      // The cursor is supplied by the service's durable coverage, never by
      // model arguments. Failed calls and unchanged pages keep the same key.
      const observed = exec.name === 'business_read_many' && typeof cursor === 'string'
        ? {...exec, arguments:{request:exec.arguments, committed_read_cursor:cursor}} : exec;
      return listener(observed, result, next);
    }, ...rest);
  }}, config);
}
