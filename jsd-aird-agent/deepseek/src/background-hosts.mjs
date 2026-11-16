/** Process/identity leases only. Native schedule remains the sole execution clock. */
export class BackgroundHosts {
  constructor(config,hosts) { Object.assign(this,{config,hosts}); }
  async control(action,body={}) {
    const response=await fetch(this.config.controlUrl+'/internal/v1/harness/background/'+action,{
      method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+this.config.secret},
      body:JSON.stringify(body),signal:AbortSignal.timeout(15000),
    });
    if(!response.ok)throw Object.assign(new Error('BACKGROUND_LEASE_REFUSED'),{status:response.status});
    return response.json();
  }
  start() {
    this.closed=false;
    this.timer=setInterval(()=>void this.refresh(),60000);this.timer.unref();
    void this.refresh();
  }
  async install(host) {
    host.installing ||= this.hosts.call(host,'automations-install').then(async plan=> {
      if(plan.grant_status==='ACTIVE') {
        const identity=await this.control('ticket',{owner_id:plan.id});
        await this.hosts.ensureBackground(identity);
      }
      return plan;
    }).catch(error=>{host.installing=undefined;throw error;});
    return host.installing;
  }
  async refresh() {
    if(this.refreshing || this.closed)return;
    this.refreshing=true;
    const eligible=new Set();
    try {
      let offset=0;const folderOwners=[];
      do {
        const page=await this.control('folder-owners',{offset});
        folderOwners.push(...page.owners);offset=page.next_offset;
      } while(offset!==undefined);
        for(const owner of folderOwners) {
          if(this.closed)return;
          try {
            const identity=await this.control('folder-ticket',{owner_id:owner.id});
            // Folder tickets are batch-scoped; never install them as the host's
            // research briefing identity. Plugin resolves tickets per session.
            const host=await this.hosts.ensure(identity,{background:true});
            await this.hosts.call(host,'folder-sync',{batch_id:owner.id});
          } catch(error) {process.stderr.write('Folder conversion recovery pending ('+(error.status || 'connection')+')\n');}
        }
    } catch(error) {process.stderr.write('Folder conversion catalog pending ('+(error.status || 'connection')+')\n');}
    try {
      let offset=0;const owners=[];
      do {
        const page=await this.control('owners',{offset});
        owners.push(...page.owners);
        offset=page.next_offset;
      } while(offset!==undefined);
      // Ticket exchange may deactivate a grant. Finish pagination first so
      // removing an owner cannot shift an unread page past the offset.
      for(const owner of owners) {
        if(this.closed)return;
        eligible.add(owner.id);
        try {
          const identity=await this.control('ticket',{owner_id:owner.id});
          const host=await this.hosts.ensureBackground(identity);
          await this.hosts.call(host,'automations-sync');
        } catch(error) {
          // Auth refusal stops the lease. Transient service failures are
          // retried next pass; the short ticket independently expires.
          if([401,403].includes(error.status)) {eligible.delete(owner.id);await this.hosts.suspendBackground(owner.id);}
          process.stderr.write('Background runtime lease unavailable ('+(error.status || 'connection')+')\n');
        }
      }
      if(!this.closed) await this.hosts.expireBackground(eligible);
    } catch(error) {process.stderr.write('Background runtime recovery pending ('+(error.status || 'connection')+')\n');}
    finally {this.refreshing=false;}
  }
  close() {this.closed=true;clearInterval(this.timer);}
}
