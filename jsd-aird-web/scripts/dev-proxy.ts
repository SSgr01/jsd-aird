import { ServerResponse, type IncomingMessage } from 'node:http';
import type { ProxyOptions } from 'vite';

/** Keep one broken upstream response from terminating the development server. */
export function createDevProxy(target: string): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    configure(proxy) {
      const responses = new WeakMap<ServerResponse, IncomingMessage>();
      const detachUpstream = (response: ServerResponse) => {
        const upstream = responses.get(response);
        responses.delete(response);
        upstream?.unpipe(response);
        upstream?.destroy();
      };

      proxy.on('proxyReq', (_upstream, _request, response) => {
        if (!(response instanceof ServerResponse)) return;
        // http-proxy can still have buffered writes when a failed request ends.
        // Handle the response's error event, rather than leaving it unhandled.
        response.once('error', () => {
          detachUpstream(response);
          response.destroy();
        });
      });

      proxy.on('proxyRes', (upstream, _request, response) => {
        if (!(response instanceof ServerResponse)) return;
        responses.set(response, upstream);
        response.once('close', () => {
          if (response.writableFinished) responses.delete(response);
          else detachUpstream(response);
        });
        upstream.once('error', () => {
          detachUpstream(response);
          response.destroy();
        });
      });

      proxy.on('error', (_error, _request, response) => {
        if (!(response instanceof ServerResponse)) return;
        detachUpstream(response);
        if (response.destroyed || response.writableEnded) return;
        // A partial response cannot be replaced with another HTTP response.
        if (response.headersSent) {
          response.destroy();
          return;
        }
        response.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
        response.end(JSON.stringify({
          code: 'DEV_PROXY_UPSTREAM_ERROR',
          message: '开发代理连接后端失败，请稍后重试。',
        }));
      });
    },
  };
}
