import { setTimeout as delay } from 'node:timers/promises';

const reads = new Set(['catalog','ownership','restore','native-output','admission-state','background-ticket']);
const transientCodes = new Set(['ECONNRESET','ECONNREFUSED','ETIMEDOUT','EPIPE','UND_ERR_SOCKET','UND_ERR_CONNECT_TIMEOUT','UND_ERR_HEADERS_TIMEOUT']);
export const transientControlError = error => [500,502,503,504].includes(error?.status)
  || error?.name === 'TimeoutError' || transientCodes.has(error?.code) || transientCodes.has(error?.cause?.code)
  || error instanceof TypeError && error.message === 'fetch failed';

/** Retry reads and durable identities, never tool dispatch or admission counters. */
export async function recoverControlRead(action, body, operation, {signal, pause=delay, onRetry=()=>{}}={}) {
  const keyedRegistration=action==='register' && body.run_id && body.epoch && body.input_signature && Number.isInteger(body.turn);
  const keyedRenewal=action==='admission-renew' && body.run_id && Number.isInteger(body.epoch) && body.session_id;
  // Control ignores already committed native sequence numbers in its transaction.
  const keyedEvent=action==='event' && body.session_id && Number.isInteger(body.event?.seq) && body.event.seq>=0;
  const attempts=reads.has(action) || keyedRegistration || keyedRenewal || keyedEvent ? 3 : 1;
  for(let attempt=0;attempt<attempts;attempt++) {
    signal?.throwIfAborted();
    try {return await operation();}
    catch(error) {
      signal?.throwIfAborted();
      if(attempt===attempts-1 || !transientControlError(error))throw error;
      onRetry(attempt+1);
      await pause(150*(attempt+1),undefined,{signal});
    }
  }
}
