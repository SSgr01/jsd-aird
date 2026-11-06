import { spawn, execFile } from 'node:child_process';
import { createServer } from 'node:net';
import { request as httpRequest } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';
import { createWriteStream, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { randomBytes } from 'node:crypto';
import { prepareProfile } from './profile.mjs';
import { ownerKey, sameOwner } from './security.mjs';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() {
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  return port;
}
// Node's fetch can replace Host with the URL authority. The native cookie is
// audience-bound, so exchange it with the exact same Host as proxied requests.
async function exchangeCookie(port, token, authority) {
  return new Promise((resolve, reject) => {
    const req = httpRequest({hostname:'127.0.0.1',port,path:'/?token='+token,
      headers:{host:authority},timeout:2000}, res => {
      res.resume();
      resolve((res.headers['set-cookie'] || []).map(value=>value.split(';',1)[0]).join('; '));
    });
    req.once('timeout',()=>req.destroy(new Error('AUTH_EXCHANGE_TIMEOUT')));
    req.once('error',reject); req.end();
  });
}
export class Hosts {
  instances = new Map();
  constructor(config) { this.config = config; }
  async ensure(identity) {
    const key = ownerKey(identity);
    let pending = this.instances.get(key);
    if (pending) {
      const host = await pending;
      // Another request may already be replacing this completed instance.
      if (this.instances.get(key) !== pending) return this.ensure(identity);
      if (sameOwner(host.identity, identity) && host.child.exitCode === null) {
        if (host.identity.expires_at < Date.now() / 1000 + 300) {
          await this.call(host, 'identity', { ticket: identity.ticket });
          host.identity = identity;
        }
        return host;
      }
      // Publish the replacement before awaiting process shutdown, so every
      // concurrent request for this owner joins the same restart.
      pending = (async () => { await this.stop(host); return this.start(identity); })();
    } else {
      if (this.instances.size >= this.config.maxHosts) throw Object.assign(new Error('HOST_CAPACITY_REACHED'), { status: 503 });
      pending = this.start(identity);
    }
    const replacement = pending.catch(error => {
      if (this.instances.get(key) === replacement) this.instances.delete(key);
      throw error;
    });
    this.instances.set(key, replacement);
    return replacement;
  }
  async start(identity) {
    const config = this.config;
    const home = resolve(config.root, ownerKey(identity));
    const executionRoot = resolve(config.executionRoot, ownerKey(identity));
    await mkdir(executionRoot, { recursive: true });
    await writeFile(resolve(executionRoot, '.dsh-workspace-root'), '');
    await mkdir(resolve(home, 'attachments'), { recursive: true });
    const patch = await prepareProfile(config, home, executionRoot);
    const port = await freePort();
    const path = '/agent/';
    const internalKey = randomBytes(32).toString('base64url');
    const env = {
      ...Object.fromEntries(['PATH', 'Path', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT',
        'TEMP', 'TMP', 'USERPROFILE', 'HOME', 'APPDATA', 'LOCALAPPDATA', 'LANG', 'LC_ALL',
        'JSD_AIRD_DSH_API_KEY'].filter(key => process.env[key] !== undefined).map(key => [key, process.env[key]])),
      DSH_HOME: home, DSH_TELEMETRY_DISABLED: '1',
      // A managed execution folder may live under the application checkout.
      // Native Git snapshots must never discover that parent repository.
      GIT_CEILING_DIRECTORIES: dirname(executionRoot).replaceAll('\\','/'),
      JSD_DSH_IDENTITY: JSON.stringify(identity),
      JSD_DSH_INTERNAL_KEY: internalKey, JSD_DSH_CONTROL_URL: config.controlUrl,
      JSD_DSH_RUNTIME_SECRET: config.secret, JSD_DSH_EXECUTION_ROOT: executionRoot,
      JSD_DSH_OFFICE_SKILLS: resolve(config.upstream, 'packages/skill/skill-office/assets'),
    };
    // Invoke the supported upstream launcher; no fork of the execution loop.
    const bin = resolve(config.upstream, 'apps/cli/lib/bin.js');
    if (!existsSync(bin)) throw new Error('DEEPSEEK_BUILD_REQUIRED');
    const child = spawn(config.node, [bin, '--profile', 'web', '--patch', patch, '--no-open', '--host', '127.0.0.1',
      '--port', String(port), '--public-url', config.origin + path, '--trusted-host', new URL(config.origin).host,
      ], { cwd: executionRoot, env, windowsHide: true, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    const log = createWriteStream(resolve(home, 'host.log'), { flags: 'a' });
    let startup = '';
    let launchToken;
    child.stdout.on('data', chunk => {
      startup = (startup + chunk.toString()).slice(-64000);
      const match = startup.match(/[?&]token=([A-Za-z0-9_-]+)/);
      if (match) launchToken = match[1];
      log.write(chunk.toString().replace(/([?&]token=)[A-Za-z0-9_-]+/g, '$1[redacted]'));
    });
    child.stderr.on('data', chunk => log.write(chunk.toString().replace(/([?&]token=)[A-Za-z0-9_-]+/g, '$1[redacted]')));
    let spawnError; child.once('error', error => { spawnError = error; });
    child.once('exit', () => log.end());
    const host = { child, port, path, identity, internalKey, home, executionRoot, cookie: '' };
    const deadline = Date.now() + (config.hostStartTimeoutMs ?? 300000);
    while (Date.now() < deadline) {
      if (spawnError || child.exitCode !== null) throw new Error('DEEPSEEK_HOST_START_FAILED');
      try {
        if (launchToken) {
          host.cookie = await exchangeCookie(port, launchToken, new URL(config.origin).host);
          if (host.cookie) break;
        }
      } catch { /* listener is still binding */ }
      await pause(100);
    }
    if (!host.cookie) { await this.stop(host); throw new Error('DEEPSEEK_AUTH_EXCHANGE_FAILED'); }
    return host;
  }
  async call(host, action, body = {}) {
    const response = await fetch(`http://127.0.0.1:${host.port}/enterprise/${action}`, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + host.internalKey },
      body: JSON.stringify(body), signal: AbortSignal.timeout(120000),
    });
    if (!response.ok) { process.stderr.write(`DeepSeek ${action} refused (${response.status})\n`); throw Object.assign(new Error('DEEPSEEK_COMMAND_REFUSED'), { status: response.status }); }
    return response.json();
  }
  async stop(host) {
    if (!host.child.pid) return;
    if (process.platform === 'win32') {
      await new Promise(resolve => execFile('taskkill.exe', ['/PID', String(host.child.pid), '/T', '/F'],
        { windowsHide: true }, () => resolve()));
    } else {
      try { process.kill(-host.child.pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    }
  }
  async close() {
    const pending = [...this.instances.values()]; this.instances.clear();
    await Promise.all(pending.map(async value => { const host = await value.catch(() => null); if (host) await this.stop(host); }));
  }
}
