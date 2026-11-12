import {randomUUID} from 'node:crypto';
import {symbols} from '../../../vendor/deepseek-harness/vendor/cordis/lib/index.js';
import {presetForProfile} from '../../deepseek/src/session-policy.mjs';

/** Use the native main Agent and native sessions for every expert conversation. */
export function installExperts(ctx, {business, executionRoot}) {
  const controller = ctx.sessionController?.[symbols.original] ?? ctx.sessionController;
  // The native fork copies messages. Carry the pinned role in the business
  // projection before releasing the child to any client.
  const descriptor = Object.getOwnPropertyDescriptor(controller, 'fork');
  const original = controller.fork;
  if (typeof original === 'function') {
    const fork = async function(request, ...rest) {
      const source = await business('experts', {action:'session',session_id:request.sessionId});
      if (source.expert) await business('experts', {action:'resolve',expert_id:source.expert.id,revision:source.expert.revision});
      const result = await Reflect.apply(original, this, [request,...rest]);
      if (source.expert) await business('experts', {action:'fork',source_session_id:request.sessionId,session_id:result.sessionId});
      return result;
    };
    Object.defineProperty(controller, 'fork', {configurable:true,writable:true,value:fork});
    ctx.effect(()=>()=>{
      if (controller.fork !== fork) return;
      if (descriptor) Object.defineProperty(controller, 'fork', descriptor);
      else delete controller.fork;
    });
  }
  return async body => {
    if (!['list','session','pin','summon'].includes(body.action)) throw new Error('EXPERT_ACTION_DENIED');
    if (body.action !== 'summon') return business('experts',body);
    // Validate first; no arbitrary prompt/skills/preset enter through the client.
    const expert = await business('experts',{action:'resolve',expert_id:body.expert_id});
    const value = await ctx.sessionController.create({cwd:executionRoot,agentPreset:presetForProfile(),sessionId:'session-'+randomUUID()});
    await business('experts',{action:'bind',session_id:value.sessionId,expert_id:expert.id,revision:expert.revision});
    return {session_id:value.sessionId,expert};
  };
}
