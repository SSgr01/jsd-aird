/** Diagnostic output only. Never logs request content, tickets, URLs or tool arguments. */
export class StageTiming {
  constructor(_logger, enabled=process.env.JSD_DSH_STAGE_TIMING==='true', write=line=>process.stderr.write(line)) {
    this.enabled=enabled;this.write=write;
  }
  emit(stage,fields) {
    // Native logger exporters may suppress INFO; diagnostics must not depend on UI verbosity.
    if(this.enabled)this.write('HARNESS_STAGE '+JSON.stringify({stage,...fields})+'\n');
  }
  async time(stage,fields,operation) {
    if(!this.enabled)return operation();
    const start=performance.now(), started_at_ms=Date.now();
    try {return await operation();}
    finally {this.emit(stage,{...fields,started_at_ms,elapsed_ms:performance.now()-start});}
  }
}
