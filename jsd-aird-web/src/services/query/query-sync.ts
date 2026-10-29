import { generateUUID } from '@/utils/uuid';

export interface CacheScope {
  organizationId: string;
  userId: string;
  authVersion: number;
}
interface SyncChannel {
  onmessage: ((event: MessageEvent<unknown>) => void) | null;
  postMessage: (message: unknown) => void;
  close: () => void;
}
interface SyncOptions {
  scope: () => CacheScope | null;
  invalidate: (domains: string[]) => void;
  session: (action: 'changed' | 'ended') => void;
}
const allowedDomains = new Set([
  'projects',
  'tasks',
  'experiments',
  'knowledge',
  'data',
  'inventory',
]);

/** Only invalidation metadata crosses tabs; never cache contents or credentials. */
export function createQuerySync(options: SyncOptions, channel: SyncChannel) {
  const sender = generateUUID();
  channel.onmessage = (event) => {
    const value: unknown = event.data;
    if (
      !value ||
      typeof value !== 'object' ||
      !('version' in value) ||
      value.version !== 1 ||
      !('sender' in value) ||
      typeof value.sender !== 'string' ||
      value.sender === sender ||
      !('type' in value)
    )
      return;
    if (
      value.type === 'session' &&
      'action' in value &&
      (value.action === 'changed' || value.action === 'ended')
    ) {
      options.session(value.action);
      return;
    }
    if (
      value.type !== 'invalidate' ||
      !('scope' in value) ||
      !('domains' in value) ||
      !Array.isArray(value.domains)
    )
      return;
    const scope = value.scope;
    const current = options.scope();
    if (
      !current ||
      !scope ||
      typeof scope !== 'object' ||
      !('organizationId' in scope) ||
      !('userId' in scope) ||
      !('authVersion' in scope) ||
      scope.organizationId !== current.organizationId ||
      scope.userId !== current.userId ||
      scope.authVersion !== current.authVersion
    )
      return;
    const domains = value.domains.filter(
      (domain): domain is string => typeof domain === 'string' && allowedDomains.has(domain),
    );
    if (domains.length) options.invalidate([...new Set(domains)]);
  };
  const send = (message: object) => {
    try {
      channel.postMessage({ version: 1, sender, ...message });
    } catch {
      /* Focus/polling remains available if the channel closes. */
    }
  };
  return {
    invalidate: (domains: readonly string[]) => {
      const scope = options.scope();
      if (scope)
        send({
          type: 'invalidate',
          scope,
          domains: domains.filter((domain) => allowedDomains.has(domain)),
        });
    },
    session: (action: 'changed' | 'ended') => send({ type: 'session', action }),
    dispose: () => {
      channel.onmessage = null;
      channel.close();
    },
  };
}

let bridge: ReturnType<typeof createQuerySync> | undefined;
export function installQuerySync(options: SyncOptions) {
  if (bridge || typeof BroadcastChannel === 'undefined') return;
  try {
    bridge = createQuerySync(options, new BroadcastChannel('aird-business-cache-v1'));
  } catch {
    /* Unsupported browser/private context: rely on polling. */
  }
}
export function publishCacheInvalidation(domains: readonly string[]) {
  bridge?.invalidate(domains);
}
export function publishSessionChange(action: 'changed' | 'ended') {
  bridge?.session(action);
}
