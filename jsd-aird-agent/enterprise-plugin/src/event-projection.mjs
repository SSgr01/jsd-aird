/** Native logs retain full events. Only these events have business projection semantics. */
export const businessEvents=new Set(['assistant/message','user/message','turn/end','agent/inbox/spliced']);

export class EventProjection {
  constructor({enqueue,send,delay=150,onCoalesce=()=>{}}) {
    Object.assign(this,{enqueue,send,delay,onCoalesce});this.pending=new Map();this.failures=new Map();
  }
  submit(sessionId,event) {
    return this.enqueue(sessionId,async()=>{
      this.check(sessionId);
      try {return await this.send(sessionId,event);}
      catch(error) {this.failures.set(sessionId,error);throw error;}
    });
  }
  check(sessionId) {if(this.failures.has(sessionId))throw this.failures.get(sessionId);}
  push(sessionId,event) {
    if(businessEvents.has(event.type)) {
      this.flush(sessionId);
      return this.submit(sessionId,event);
    }
    let pending=this.pending.get(sessionId);
    if(!pending) {
      pending={count:0,event,timer:setTimeout(()=>this.flush(sessionId),this.delay)};
      pending.timer.unref?.();this.pending.set(sessionId,pending);
    }
    pending.count++;
    // Preserve routing and the last durable sequence, never transport model contexts or tool results again.
    pending.event={seq:event.seq,type:event.type,data:{turn:event.data?.turn,step:event.data?.step}};
  }
  flush(sessionId) {
    const pending=this.pending.get(sessionId);
    if(!pending)return;
    clearTimeout(pending.timer);this.pending.delete(sessionId);
    this.onCoalesce({session_id:sessionId,events:pending.count});
    return this.submit(sessionId,pending.event);
  }
  clear(sessionId) {const pending=this.pending.get(sessionId);if(pending)clearTimeout(pending.timer);this.pending.delete(sessionId);this.failures.delete(sessionId);}
}

/** Only legacy text deltas need HTTP projection; native subscribers retain all frames. */
export class StreamProjection {
  constructor({enqueue,send,delay=500}) {Object.assign(this,{enqueue,send,delay});this.pending=new Map();}
  push(sessionId,binding,frame) {
    if(frame.type!=='chunk' || !['text-delta','text_delta'].includes(frame.chunk?.type))return;
    let buffer=this.pending.get(sessionId);
    if(!buffer) {buffer={frames:[],binding,timer:null,queued:false};this.pending.set(sessionId,buffer);}
    buffer.frames.push(frame);
    if(!buffer.timer && !buffer.queued) {
      buffer.timer=setTimeout(()=>this.flush(sessionId),this.delay);buffer.timer.unref?.();
    }
  }
  flush(sessionId) {
    const buffer=this.pending.get(sessionId);
    if(!buffer)return;
    clearTimeout(buffer.timer);buffer.timer=null;
    if(buffer.queued || !buffer.frames.length)return;
    buffer.queued=true;
    return this.enqueue(sessionId,async()=>{
      try {await this.send(sessionId,buffer.binding,buffer.frames.splice(0));}
      finally {
        buffer.queued=false;
        if(buffer.frames.length)this.flush(sessionId);
        else if(this.pending.get(sessionId)===buffer)this.pending.delete(sessionId);
      }
    });
  }
  clear(sessionId) {const buffer=this.pending.get(sessionId);if(buffer)clearTimeout(buffer.timer);this.pending.delete(sessionId);}
}
