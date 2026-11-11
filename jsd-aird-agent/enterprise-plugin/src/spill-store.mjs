import { randomUUID, createHash } from 'node:crypto';
import { posix } from 'node:path';
import { SpillStore, SpillLocator } from '../../../vendor/deepseek-harness/packages/spill/spill/lib/index.js';

/** Native spill policy, backed by the same filesystem as read/glob/grep/bash. */
export default class EnterpriseSpillStore extends SpillStore {
  static inject = ['fs', 'agents', 'workingDirectory'];
  async saveText(input) {
    const agent = this.ctx.agents.get(input.owner.sessionId);
    if (!agent) throw new Error('SPILL_SESSION_UNAVAILABLE');
    const cwd = this.ctx.workingDirectory.get(agent.session);
    const session = createHash('sha256').update(input.owner.sessionId).digest('hex').slice(0, 24);
    const name = (input.suggestedName || 'output.txt').replace(/[^a-zA-Z0-9._-]/g, '_').slice(-80) || 'output.txt';
    const target = await this.ctx.fs.resolve(posix.join('.dsh-spill', session, randomUUID() + '-' + name), { cwd });
    const content = Buffer.from(input.content, 'utf8');
    await this.ctx.fs.publish(String(target.targetKey), content);
    const locator = SpillLocator(this.ctx.fs.processPath(target));
    return { locator, bytes: content.length, retrievalHint: `Read the full output at ${JSON.stringify(locator)} using read, grep, or bash.` };
  }
}
