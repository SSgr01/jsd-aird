import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

export const previewTurnInput = agent => [...agent.inbox.nextStep, ...agent.inbox.nextTurn.slice(0, 1)];
export const inputSignature = messages => createHash('sha256').update(JSON.stringify(messages)).digest('hex');

/** Admission owns leases; this adapter waits before the native loop consumes input. */
export function installAdmission(ctx, { business, prepare, ready }) {
  const grants = new Map();
  const stop = sessionId => {
    const grant = grants.get(sessionId);
    if (grant) {
      grant.stopped = true;
      clearTimeout(grant.timer); clearTimeout(grant.deadline);
      grant.renewal?.abort(); grants.delete(sessionId);
    }
    return grant;
  };
  const receipt = (sessionId, grant) => ({ session_id: sessionId, run_id: grant.run_id, epoch: grant.epoch });
  const active = (agent, grant) => !grant.stopped && grants.get(agent.id) === grant;
  function fence(agent, grant, code) {
    if (!active(agent, grant)) return;
    grant.stopped = true;
    clearTimeout(grant.timer); clearTimeout(grant.deadline); grant.renewal?.abort();
    ctx.logger.warn('Run lease fenced run=%s epoch=%s reason=%s', grant.run_id, grant.epoch, code);
    agent.cancel({ kind: 'user' }, { keepInbox:true });
  }
  function deadline(agent, grant) {
    clearTimeout(grant.deadline);
    grant.deadline = setTimeout(() => fence(agent, grant, 'LEASE_EXPIRED'),
      Math.max(1, grant.confirmedAt + grant.lease_seconds*1000 - Date.now()));
    grant.deadline.unref();
  }
  function heartbeat(agent, grant) {
    grant.timer = setTimeout(async () => {
      if (!active(agent, grant)) return;
      const requestedAt = Date.now();
      const renewal = new AbortController();
      grant.renewal = renewal;
      // Leave time for another renewal before the lease expires. A normal
      // tool request can wait longer than the entire lease and cannot share
      // the heartbeat's deadline.
      const timeout = AbortSignal.timeout(Math.max(1, Math.min(5000,
        grant.confirmedAt + grant.lease_seconds*1000 - requestedAt)));
      try {
        const result = await business('admission-renew', receipt(agent.id, grant),
          AbortSignal.any([renewal.signal, timeout]));
        if (!active(agent, grant)) return;
        if (result.terminal) { stop(agent.id); return; }
        // The server renewed during this request, not when its reply arrived.
        grant.confirmedAt = requestedAt;
        deadline(agent, grant);
      } catch (error) {
        if (!active(agent, grant)) return;
        // A confirmed fence prevents further calls. A brief transport outage is
        // retried; the control service still rejects an expired generation.
        if (error.status === 409 || error.status === 403 || Date.now()-grant.confirmedAt >= grant.lease_seconds*1000) {
          fence(agent, grant, error.code || String(error.status || 'LEASE_EXPIRED')); return;
        }
        ctx.logger.warn('Run lease renewal pending: %s', error.code || 'connection');
      }
      if (active(agent, grant)) heartbeat(agent, grant);
    }, grant.heartbeat_seconds * 1000);
    grant.timer.unref();
  }
  ctx.on('agent/pre-turn', async ({ agent, messages, turn, signal }, next) => {
    await ready;
    const signature = inputSignature(messages);
    const previous = grants.get(agent.id);
    if (previous) {
      stop(agent.id);
      await business('admission-release', { ...receipt(agent.id, previous), cancelled: true, stopped: true });
    }
    const input = await prepare(agent, { messages, turn, signal, input_signature: signature });
    let state;
    try {
      // Learn the durable ticket even if the user cancels during this request,
      // so its cancellation can be acknowledged rather than orphaning a run.
      while (!state) {
        try { state = await business('admit', input); }
        catch (error) {
          if (error.status && error.status < 500) throw error;
          signal.throwIfAborted();
          await delay(1500, undefined, { signal });
        }
      }
      while (true) {
        signal.throwIfAborted();
        if (inputSignature(previewTurnInput(agent)) !== signature) {
          await business('admission-release', { session_id: agent.id, run_id: state.run_id, epoch: state.epoch, cancelled: true, stopped: true });
          return next();
        }
        if (['CANCELLED', 'FAILED', 'SUCCEEDED'].includes(state.status)) throw Object.assign(new Error('任务已停止，请重试'), { code: 'ADMISSION_STOPPED' });
        if (state.admission === 'GRANTED') break;
        await delay(750, undefined, { signal });
        try { state = await business('admission-state', { session_id: agent.id, run_id: state.run_id }, signal); }
        catch (error) {
          if (signal.aborted || (error.status && error.status < 500)) throw error;
          await delay(1500, undefined, { signal });
        }
      }
      const grant = { ...state, lease_seconds:state.lease_seconds || 60, confirmedAt:Date.now(), turn, inputSignature:signature, stopped: false };
      grants.set(agent.id, grant);
      deadline(agent, grant);
      heartbeat(agent, grant);
      await next();
    } catch (error) {
      stop(agent.id);
      if (!state && signal.aborted && signal.reason?.kind !== 'disposed') {
        // A lost initial reply may still have committed a ticket. Reuse the
        // same input identity and persist cancellation before a late admission
        // request can enter it. Disposed hosts deliberately retain their work.
        try { state = await business('admit', {...input,cancelled:true}); }
        catch (cleanup) { ctx.logger.warn('Waiting request cancellation pending: %s', cleanup.code || 'connection'); }
      }
      // Disposing a host preserves its durable Inbox and ticket for recovery.
      if (state && signal.reason?.kind !== 'disposed') {
        try {
          if (!signal.aborted) await business('failure', { session_id: agent.id, run_id: state.run_id, epoch: state.epoch,
            diagnostic: { code: error.code || 'ADMISSION_FAILED', phase: 'admission' } });
        } finally {
          await business('admission-release', { session_id: agent.id, run_id: state.run_id, epoch: state.epoch,
            cancelled: signal.aborted, stopped: true })
            .catch(cleanup => ctx.logger.warn('Run lease release pending: %s', cleanup.code || 'connection'));
        }
      }
      throw error;
    }
  });
  ctx.on('session/event', (session, event) => {
    if (event.type !== 'turn/end') return;
    const grant = grants.get(session.id);
    if (!grant || grant.turn !== event.data.turn) return;
    stop(session.id);
    // Activation can fail before a Run is bound to the enterprise tool layer.
    // The admission ticket still identifies the queued Run that must be ended.
    const failure=event.data.reason?.kind==='error'
      ? business('failure',{...receipt(session.id,grant),diagnostic:{code:event.data.reason.error?.code,phase:'native-activation'},error_code:event.data.reason.error?.code})
      : Promise.resolve();
    void failure.finally(()=>business('admission-release', { ...receipt(session.id, grant), stopped: true }))
      .catch(error => ctx.logger.warn('Run lease release pending: %s', error.code || 'connection'));
  });
  ctx.effect(() => () => { for (const id of grants.keys()) stop(id); });
  return { grants };
}
