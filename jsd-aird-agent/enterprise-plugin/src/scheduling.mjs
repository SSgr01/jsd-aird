import { executionDirectory } from '../../deepseek/src/paths.mjs';

export const scheduleTools = new Set(['schedule_create', 'schedule_list', 'schedule_view', 'schedule_edit']);

/** Only human input and the Host scheduler supply a new enterprise task. */
export function requestText(messages) {
  return messages.filter(message => ['user','schedule'].includes(message.source?.kind))
    .flatMap(message => message.content.filter(part => part.type === 'text').map(part => part.text)).join('\n');
}

/** Admission runs on every scheduled activation, including an already-live Agent. */
export function installScheduling(ctx, {business, sessionProfile, executionRoot}) {
  ctx.on('schedule/resolve-agent', async (sessionId, next) => {
    // This native activation event has no caller signal; the session controller
    // still requires a live signal when reading its projections.
    const signal = new AbortController().signal;
    // Check identity before activation, including a reserved Session which does
    // not exist yet. Let native resolution preserve its typed not-found error so
    // the scheduler can create that Session through its normal provider.
    await business('catalog', {profile:'agent'}, signal);
    const agent = await next();
    const profile = await sessionProfile(sessionId, signal);
    await business('ownership', {session_id:sessionId, profile}, signal);
    await executionDirectory(executionRoot, await ctx.workingDirectory.ensure(agent));
    return agent;
  });
}
