import {randomUUID} from 'node:crypto';
import {symbols} from '../../../vendor/deepseek-harness/vendor/cordis/lib/index.js';
import {presetForProfile} from '../../deepseek/src/session-policy.mjs';
import {executionDirectory} from '../../deepseek/src/paths.mjs';
import {ensureDefaultWorkspace} from './default-workspace.mjs';

/** Use the native main Agent and native sessions for every expert conversation. */
export function installExperts(ctx, {business, executionRoot}) {
  const controller = ctx.sessionController?.[symbols.original] ?? ctx.sessionController;
  // Native New Session may nominate any blank workspace member for reuse.
  // An expert-bound blank must not become the default ordinary conversation.
  const createDescriptor=Object.getOwnPropertyDescriptor(controller,'create');
  const originalCreate=controller.create;
  if(typeof originalCreate==='function') {
    const create=async function(request,...rest) {
      const workspace=request.workspaceId && ctx.workspaceRegistry.get?.(request.workspaceId);
      if(request.sessionId && workspace?.sessionIds.includes(request.sessionId)) {
        const source=await business('experts',{action:'session',session_id:request.sessionId});
        if(source.expert) {const {sessionId,...ordinary}=request;request=ordinary;}
      }
      return Reflect.apply(originalCreate,this,[request,...rest]);
    };
    Object.defineProperty(controller,'create',{configurable:true,writable:true,value:create});
    ctx.effect(()=>()=>{
      if(controller.create!==create)return;
      if(createDescriptor)Object.defineProperty(controller,'create',createDescriptor);
      else delete controller.create;
    });
  }
  // The native fork copies messages. Carry the expert identity in the business
  // projection before releasing the child to any client.
  const descriptor = Object.getOwnPropertyDescriptor(controller, 'fork');
  const original = controller.fork;
  if (typeof original === 'function') {
    const fork = async function(request, ...rest) {
      const source = await business('experts', {action:'session',session_id:request.sessionId});
      if (source.expert) await business('experts', {action:'resolve',expert_id:source.expert.id});
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
    if(body.action==='general') {
      const workspace=await ensureDefaultWorkspace(ctx.workspaceRegistry,executionRoot);
      const value=await ctx.sessionController.create({workspaceId:workspace.id,agentPreset:presetForProfile(),sessionId:'session-'+randomUUID()});
      return {session_id:value.sessionId,expert:null};
    }
    if (!['list','session','pin','summon'].includes(body.action)) throw new Error('EXPERT_ACTION_DENIED');
    if (body.action !== 'summon') return business('experts',body);
    // Validate first; no arbitrary prompt/skills/preset enter through the client.
    const expert = await business('experts',{action:'resolve',expert_id:body.expert_id});
    // The native composer requires Workspace membership. The execution root
    // contains workspaces; it is not itself the default Workspace directory.
    const directory = await executionDirectory(executionRoot,'/workspaces/default');
    const workspace = await ctx.workspaceRegistry.create(directory);
    const value = await ctx.sessionController.create({workspaceId:workspace.id,agentPreset:presetForProfile(),sessionId:'session-'+randomUUID()});
    await business('experts',{action:'bind',session_id:value.sessionId,expert_id:expert.id});
    return {session_id:value.sessionId,expert};
  };
}
