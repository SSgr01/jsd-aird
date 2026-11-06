import { installNativeRuntime, nativeTools } from './native-runtime.mjs';
import { randomUUID, createHash } from 'node:crypto';
import { timingSafeEqual } from 'node:crypto';
import { presetForProfile, profileForPreset } from '../../deepseek/src/session-policy.mjs';
import { executionDirectory } from '../../deepseek/src/paths.mjs';
import { installScheduling, requestText, scheduleTools } from './scheduling.mjs';
import { businessError, deliveryError } from './business-error.mjs';
import { mergeBusinessContext } from './business-context.mjs';
import { updateNativeInbox } from './inbox-actions.mjs';
import { citationStream } from './citation-stream.mjs';
import { modelResult, presentation } from './model-view.mjs';
import { createUserMessage, createAssistantMessage, BlockAssembler } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

export const name = 'jsd-enterprise';
export const inject = ['tools', 'agents', 'sessions', 'systemPrompt', 'webServer', 'sessionController', 'llm', 'agentDefaultModel', 'connection', 'workspaceRegistry', 'agentPresets', 'workingDirectory', 'attachments', 'fs', 'web'];

/** Host-owned adapter. Identity and tool-call keys never come from model arguments. */
export async function apply(ctx) {
  const identity = JSON.parse(process.env.JSD_DSH_IDENTITY);
  const control = process.env.JSD_DSH_CONTROL_URL;
  const secret = process.env.JSD_DSH_RUNTIME_SECRET;
  const internalKey = process.env.JSD_DSH_INTERNAL_KEY;
  const executionRoot = process.env.JSD_DSH_EXECUTION_ROOT;
  const profileOf = agent => profileForPreset(ctx.agentPresets.presetForSession(agent.session));
  async function sessionProfile(sessionId, signal) {
    const state = await ctx.sessionController.projections({ sessionId }, signal);
    if (!state) throw new Error('SESSION_NOT_FOUND');
    return profileForPreset(state.values.agentPreset);
  }
  const runs = new Map();
  const serial = new Map();
  const cancelled = new Set();
  const pendingEvents = new Map();
  const streamBuffers = new Map();
  const outputLimited = new Set();
  const claimedInputs = new Map();
  const currentTurns = new Map();
  const preparedBindings = new Map();
  async function business(action, body, signal) {
    const response = await fetch(control + '/internal/v1/harness/dsh/' + action, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + secret,
        'x-jsd-agent-ticket': identity.ticket }, body: JSON.stringify(body),
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(120000)]) : AbortSignal.timeout(120000),
    });
    if (!response.ok) throw await businessError(response);
    return response.json();
  }
  function enqueue(sessionId, operation) {
    const next = (serial.get(sessionId) || Promise.resolve()).catch(() => {}).then(operation);
    serial.set(sessionId, next);
    // Persistence remains in DeepSeek. A failed projection is replayed from its log on restore.
    void next.catch(error => ctx.logger.warn('Enterprise projection pending: %s', error.message));
    return next;
  }
  const catalog = await business('catalog', { profile: 'agent' });
  const native = installNativeRuntime(ctx,{business,runs,profileOf,searchAvailable:catalog.web_search_available === true});
  for (const spec of catalog.tools) {
    ctx.effect(() => ctx.tools.register({
      ...spec,
      output: {
        schema: { type: 'object', additionalProperties: true },
        render: (_args, value) => [{ type: 'text', text: JSON.stringify(modelResult(value)) }],
        presentationMeta: (_args, value) => presentation(spec.name, value),
      },
      async execute(args, execution) {
        const bound = runs.get(execution.agent?.id);
        if (!bound || !bound.tools.some(tool => tool.name === spec.name)) throw new Error('CAPABILITY_NOT_AUTHORIZED');
        await business('gate', { run_id: bound.id, session_id: execution.agent.id, turn: bound.dsh_turn,
          kind: 'tool', call_id: execution.callId }, execution.signal);
        if(spec.name==='artifact_save')return native.register(execution,args.path,0,args.source_quotes||[]);
        const result = await business('tool', { run_id: bound.id, session_id: execution.agent.id,
          turn: bound.dsh_turn, call_id: execution.callId, name: spec.name, arguments: args }, execution.signal);
        if (result.activate_tools) bound.active_tools = [...new Set([...(bound.active_tools || []),
          ...result.activate_tools.filter(name => bound.tools.some(tool => tool.name === name))])];
        Object.assign(bound.citation_links ||= {}, result.citation_links || {});
        return result;
      },
    }, { when: async ({ agent, signal }) => {
      const bound = await prepareBinding(agent, signal);
      return Boolean(bound?.tools.some(tool => tool.name === spec.name) && bound.active_tools?.includes(spec.name));
    } }));
  }
  ctx.effect(() => ctx.tools.guard(execution => {
    if (!execution.agent) return 'ENTERPRISE_TOOL_DENIED';
    const bound = runs.get(execution.agent.id);
    return (nativeTools.has(execution.name) && bound && (scheduleTools.has(execution.name) || bound.pack_ids?.includes('files'))) || bound?.tools.some(tool => tool.name === execution.name)
      ? undefined : 'ENTERPRISE_TOOL_DENIED';
  }));
  ctx.effect(() => ctx.systemPrompt.section({ name: 'enterprise-policy', order: 0,
    interpolate: false, text: ({ agent }) => (runs.get(agent?.id)?.instructions || catalog.instructions) + '\n原生文件与 bash 命令在 Linux 沙箱的同一执行空间中运行。使用会话相对路径，不在脚本中使用宿主 Windows 路径。' }));
  ctx.effect(() => ctx.systemPrompt.context({ name: 'enterprise-business', order: 0, required: true,
    interpolate: false, text: ({ agent }) => runs.get(agent?.id)?.context || '' }));
  ctx.on('agent/created', async ({ agent, source, signal }) => {
    await executionDirectory(executionRoot, ctx.workingDirectory.get(agent.session));
    if (source === 'resume') {
      const profile = profileOf(agent);
      const restored = await business('restore', { session_id: agent.id, profile }, signal);
      if (restored.run) runs.set(agent.id, restored.run);
      if (profile === 'agent' && restored.run?.pack_ids.includes('files')) {
        const messages = agent.session.snapshotEvents().filter(event=>event.type==='user/message').map(event=>event.data.message||event.data);
        await exposeFiles(agent, (await inputFiles(messages, signal)).files, signal);
      }
      // Reconcile committed events; no pi checkpoints and no model-state copy.
      for (const event of agent.session.snapshotEvents()) await enqueue(agent.id,
        () => business('event', { session_id: agent.id, event }));
    }
  });
  ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
    currentTurns.set(agent.id, turn);
    const input = claimedInputs.get(agent.id) || {turn, messages:[]};
    input.messages.push(message);
    claimedInputs.set(agent.id, input);
  });
  installScheduling(ctx, {business, sessionProfile, executionRoot});
  // dsh resolves asynchronous tool conditions before assembling the system
  // prompt, then invokes pre-step. Bind the claimed Inbox batch at that native
  // admission boundary so the first request has its actual policy and tools.
  async function prepareBinding(agent, signal, proposal) {
    if (preparedBindings.has(agent.id)) return preparedBindings.get(agent.id);
    const input = proposal || claimedInputs.get(agent.id) || {turn:currentTurns.get(agent.id),messages:[]};
    if (input.turn === undefined) return undefined;
    claimedInputs.delete(agent.id);
    const pending = bindInput(agent, signal, input);
    preparedBindings.set(agent.id, pending);
    try { return await pending; }
    catch (error) { preparedBindings.delete(agent.id); throw error; }
  }
  async function bindInput(agent, signal, input) {
    await executionDirectory(executionRoot, await ctx.workingDirectory.ensure(agent, signal));
    const profile = profileOf(agent);
    await serial.get(agent.id)?.catch(() => {});
    const text = requestText(input.messages);
    const {attachments, files} = await inputFiles(input.messages, signal);
    if (files.length && profile === 'agent') await exposeFiles(agent, files, signal);
    const bound = await business('register', { session_id: agent.id, turn: input.turn,
      profile, request: text, attachments }, signal);
    const previous = runs.get(agent.id);
    if (!input.messages.length && previous?.dsh_turn === input.turn)
      bound.context = mergeBusinessContext(previous.context, bound.context);
    runs.set(agent.id, bound);
    const waiting = pendingEvents.get(agent.id) || [];
    pendingEvents.delete(agent.id);
    for (const event of waiting) await enqueue(agent.id, () => business('event', {session_id:agent.id,event}));
    cancelled.delete(agent.id);
    return bound;
  }
  async function inputFiles(messages, signal) {
    const attachments = [], files = [];
    for (const message of messages.filter(message => message.source?.kind === 'user')) {
      for (const part of message.content.filter(part => part.type === 'file')) {
        if (part.attachment.bytes > 10 * 1024 * 1024) throw new Error('DOCUMENT_PARSE_LIMIT');
        const chunks = []; let bytes = 0;
        for await (const chunk of ctx.attachments.readFileStream(part.attachment, signal)) {
          bytes += chunk.byteLength;
          if (bytes > 10 * 1024 * 1024) throw new Error('DOCUMENT_PARSE_LIMIT');
          chunks.push(chunk);
        }
        const file = Buffer.concat(chunks);
        if (file.length !== part.attachment.bytes || 'sha256:' + createHash('sha256').update(file).digest('hex') !== part.attachment.attachmentId)
          throw new Error('NATIVE_ATTACHMENT_MISMATCH');
        files.push({ref:part.attachment,bytes:file});
        if (/\.(pdf|docx|txt)$/i.test(part.attachment.name))
          attachments.push({attachment_id:part.attachment.attachmentId,name:part.attachment.name,base64:file.toString('base64')});
      }
    }
    return {attachments,files};
  }
  async function exposeFiles(agent, files, signal) {
    const cwd = await ctx.workingDirectory.ensure(agent, signal);
    for (const {ref,bytes} of files)
      await ctx.fs.exposeAttachment(ref,bytes,cwd,ctx.attachments.fileHostPath(ref));
  }
  ctx.on('agent/pre-step', async (payload, next) => {
    try {
      const bound = await prepareBinding(payload.agent, payload.signal, payload);
      if (!bound || bound.dsh_turn !== payload.turn) throw new Error('TURN_BINDING_CONFLICT');
      await business('gate', { run_id: bound.id, session_id: payload.agent.id, turn: payload.turn, kind: 'step' }, payload.signal);
      return await next();
    } finally { preparedBindings.delete(payload.agent.id); }
  });
  // All native provider calls share enterprise admission and metering. Title
  // requests can precede the first Run and use their own session-bound budget.
  ctx.on('llm/stream', async function* (options, next) {
    const callId = randomUUID();
    const title = options.purpose === 'session-title';
    const bound = runs.get(options.sessionId);
    if (!title && !bound) throw new Error('UNBOUND_ENTERPRISE_SESSION');
    if (title) await sessionProfile(options.sessionId, options.signal);
    const request = { session_id: options.sessionId, call_id: callId,
      ...(!title ? { run_id: bound.id, turn: bound.dsh_turn, kind: 'model' } : {}),
      purpose: options.purpose || 'conversation', provider: options.provider, model: options.model };
    await business(title ? 'auxiliary-admit' : 'gate', request, options.signal);
    const assembler = new BlockAssembler();
    let completed = false;
    try {
      const stream = ['report-review', 'evidence-score'].includes(options.purpose) || title
        ? next() : citationStream(next(), bound.citation_links || {});
      for await (const chunk of stream) { assembler.push(chunk); yield chunk; }
      completed = true;
    } finally {
      // Cancellation may abort the inference signal; persist already observed
      // usage independently and let a metering outage leave the request pending.
      try { await business(title ? 'auxiliary-result' : 'model-result', {
        ...request, usage: assembler.usage, completed,
      }); } catch (error) { ctx.logger.warn('Model usage projection pending: %s', error.message); }
    }
  });
  ctx.on('session/event', (session, event) => {
    if (event.type === 'turn/start') {
      outputLimited.delete(session.id);
      currentTurns.set(session.id, event.data.turn);
      if (runs.get(session.id)?.dsh_turn !== event.data.turn) {
        pendingEvents.set(session.id, []);
        runs.delete(session.id);
        preparedBindings.delete(session.id);
        claimedInputs.delete(session.id);
      }
    }
    if (pendingEvents.has(session.id)) {pendingEvents.get(session.id).push(event);return;}
    enqueue(session.id, () => business('event', { session_id: session.id, event }));
  });
  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    const bound = runs.get(agent.id);
    if (!bound) return;
    if (frame.type === 'chunk' && frame.chunk.type === 'finish' && frame.chunk.reason?.kind === 'max-tokens') outputLimited.add(agent.id);
    // Coalesce legacy HTTP projection; native subscribers receive every frame directly.
    const buffer = streamBuffers.get(agent.id) || {frames:[],timer:null};
    buffer.frames.push(frame); streamBuffers.set(agent.id, buffer);
    if (!buffer.timer) buffer.timer = setTimeout(() => {
      buffer.timer = null; const frames = buffer.frames.splice(0);
      enqueue(agent.id, () => business('stream', {run_id:bound.id,session_id:agent.id,frames}));
    }, 150);
  });
  ctx.on('agent/turn-stopping', async ({ agent, turn, signal }) => {
    await serial.get(agent.id)?.catch(() => {});
    const bound = runs.get(agent.id);
    if (bound) {
      const buffer = streamBuffers.get(agent.id);
      if (buffer?.timer) { clearTimeout(buffer.timer); buffer.timer=null; await business('stream',{run_id:bound.id,session_id:agent.id,frames:buffer.frames.splice(0)}); }
      const input = { run_id: bound.id, session_id: agent.id, turn,
        ...(outputLimited.has(agent.id) ? {stop_reason:'max-tokens'} : {}) };
      // Keep the native truncation/continue flow. A cut-off response is not a
      // completed business result and must not trigger a different task.
      if (outputLimited.has(agent.id)) { await business('complete', input, signal); return; }
      const checked = await business('complete', {...input, check_only:true}, signal);
      const correctionSource = 'enterprise-citation-correction';
      const alreadyCorrected = agent.session.snapshotEvents().some(event => event.type === 'user/message'
        && event.data.source?.kind === correctionSource && event.data.source.turn === turn);
      if (checked.status === 'FAILED' && checked.completion?.gaps?.[0] === 'CITATION_INVALID'
          && profileOf(agent) === 'agent' && !alreadyCorrected) {
        agent.steer(createUserMessage({source:{kind:correctionSource,turn},content:[{type:'text',text:
          '继续完成用户原任务，只修复来源检查发现的缺失或无效引用。保留原任务的交付要求，用户要文件时继续生成并用 present 交付，不改为引用清单。关键事实附近使用实际已读证据的 [E:证据ID]；不猜句柄或把 DOI 当作原文证据。沿用已读资料，仅在必要时定向回读。反馈只有这一次，仍无法补齐时说明具体缺口。'}]}));
        return;
      }
      const result = await business('complete', input, signal);
      if (result.status === 'FAILED') throw deliveryError(result);
    }
  });
  ctx.on('agent/error', ({ agent, error }) => {
    const bound = runs.get(agent.id);
    if (bound) enqueue(agent.id, () => business('failure', { run_id: bound.id, session_id: agent.id,
      cancelled: cancelled.has(agent.id), error_code: error?.code }));
  });

  async function readBody(req) {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 20 * 1024 * 1024) throw new Error('PAYLOAD_TOO_LARGE'); chunks.push(chunk); }
    return JSON.parse(Buffer.concat(chunks).toString() || '{}');
  }
  ctx.effect(() => ctx.webServer.register({ kind: 'prefix', path: '/enterprise', async handler(req, res) {
    const provided = Buffer.from(req.headers.authorization || '');
    const expected = Buffer.from('Bearer ' + internalKey);
    if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) { res.writeHead(401); res.end(); return; }
    try {
      const body = await readBody(req);
      if (body.ticket) identity.ticket = body.ticket;
      const action = new URL(req.url, 'http://local').pathname.slice('/enterprise/'.length);
      let value;
      const signal = new AbortController().signal;
      if (action === 'identity') value = { accepted: true };
      else if (action === 'models') {
        const selected = ctx.agentDefaultModel.currentSelection();
        try {
          await ctx.llm.prepareCall(selected, signal);
          value = { available: true, ...selected };
        } catch {
          value = { available: false, ...selected };
        }
      }
      else if (action === 'create') {
        const profile = body.profile || 'agent';
        const location = body.workspace_id ? { workspaceId: body.workspace_id } : { cwd: executionRoot };
        const directory = body.workspace_id ? ctx.workspaceRegistry.get(body.workspace_id)?.path : executionRoot;
        await executionDirectory(executionRoot, directory);
        value = await ctx.sessionController.create({ ...location, agentPreset: presetForProfile(profile), sessionId: body.session_id });
        await business('bind', { run_id: body.run_id, session_id: value.sessionId, profile });
      } else {
        const sessionId = body.session_id;
        if (!sessionId) throw new Error('SESSION_REQUIRED');
        const profile = await sessionProfile(sessionId, signal);
        if (body.profile && body.profile !== profile) throw new Error('SESSION_PROFILE_CHANGED');
        await business('ownership', { session_id: sessionId, profile });
        if (action === 'prompt' || action === 'resume') {
          value = await ctx.sessionController.prompt({ sessionId, requestId: body.request_id || randomUUID(),
            mode: body.mode === 'steer' ? 'steer' : 'queue', content: [{ type: 'text', text: body.text || '继续' }],
            clientTimeZone: 'Asia/Shanghai' }, signal);
        } else if (action === 'review') {
          const purpose = body.purpose || 'report-review';
          if (!['report-review', 'evidence-score'].includes(purpose)) throw new Error('ANALYSIS_PURPOSE_DENIED');
          const bound = runs.get(sessionId);
          if (!bound || bound.id !== body.run_id) throw new Error('RUN_BINDING_CONFLICT');
          const agent = ctx.agents.get(sessionId);
          const route = agent?.model.current();
          if (!route) throw new Error('MODEL_SELECTION_REQUIRED');
          const call = await ctx.llm.prepareCall({ ...route, maxTokens: 8192 }, signal);
          const assembler = new BlockAssembler();
          const messages = body.messages.map(message => {
            const input = { content: [{ type: 'text', text: message.content }], source: { kind: 'enterprise-report-review' } };
            if (message.role === 'user') return createUserMessage(input);
            if (message.role === 'assistant') return createAssistantMessage({ ...input,
              source: { provider: call.config.provider, model: call.config.model } });
            throw new Error('REVIEW_MESSAGE_ROLE_INVALID');
          });
          for await (const chunk of call.stream({ ...call.config, system: body.system,
            messages, sessionId, purpose, signal })) assembler.push(chunk);
          if (assembler.finish?.kind !== 'stop') throw new Error('REVIEW_INCOMPLETE');
          value = { content: assembler.blocks().filter(block => block.type === 'text').map(block => block.text).join(''),
            model: call.config.model, provider: call.config.provider };
        } else if (action === 'cancel') {
          cancelled.add(sessionId); value = await ctx.sessionController.cancel({ sessionId });
        } else if (action === 'queue') {
          const kind = {delete:'remove',promote:'steer'}[body.action?.kind] || body.action?.kind;
          if (!['edit','remove','steer'].includes(kind)) throw new Error('NATIVE_QUEUE_ACTION_REQUIRED');
          const action = kind === 'edit' ? {kind,content:[{type:'text',text:body.action.text}]} : {kind};
          value = await ctx.sessionController.updateQueue({sessionId,itemId:body.item_id,action});        } else if (action === 'authorize') value = {allowed:true};
        else if (action === 'state') value = await ctx.sessionController.state({ sessionId }, signal);
        else throw new Error('UNKNOWN_COMMAND');
      }
      res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(value));
    } catch (error) { process.stderr.write('Enterprise command refused: ' + error.message + '\n'); res.writeHead(409); res.end(JSON.stringify({ error: 'COMMAND_REFUSED' })); }
  } }));

  ctx.effect(() => ctx.webServer.register({ kind: 'prefix', path: '/business', async handler(req, res) {
    const denied = ctx.connection.requestRejection({ method: req.method, headers: req.headers });
    if (denied) { res.writeHead(denied); res.end(); return; }
    const action = new URL(req.url, 'http://local').pathname.slice('/business/'.length);
    if (!['context','resource','inbox'].includes(action)) {res.writeHead(404);res.end();return;}
    try {
      const body = await readBody(req);
      let value;
      if (action === 'inbox') {
        const profile = await sessionProfile(body.session_id, new AbortController().signal);
        await business('ownership', {session_id:body.session_id,profile});
        // Restore the native durable Inbox without starting a model turn.
        const found = await ctx.sessionController.resolveAgent(body.session_id);
        if ('error' in found) throw found.error;
        value = updateNativeInbox(found.agent,body);
      } else value = await business(action, body);
      res.writeHead(200, {'content-type':'application/json','cache-control':'no-store'});
      res.end(JSON.stringify(value));
    } catch {res.writeHead(409);res.end(JSON.stringify({error:'BUSINESS_ACCESS_REFUSED'}));}
  }}));
}
