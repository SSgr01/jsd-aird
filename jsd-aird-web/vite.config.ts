import { fileURLToPath, URL } from 'node:url';
import { realpathSync } from 'node:fs';

import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';
import { buildInfoPlugin } from './scripts/build-info-plugin';
import { createDevProxy } from './scripts/dev-proxy';
import { browserCssCompat } from './scripts/browser-css-compat';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080';
  const agentTarget = env.VITE_DEV_AGENT_TARGET || process.env.VITE_DEV_AGENT_TARGET || 'http://127.0.0.1:8092';

  return {
    plugins: [react(), buildInfoPlugin()],
    css: { postcss: { plugins: [browserCssCompat()] } },
    resolve: {
      // Keep linked dependency assets on the checkout's drive. Vite's raw-file
      // middleware otherwise drops the drive letter on cross-drive junctions.
      preserveSymlinks: true,
      // Univer declares RxJS as a peer dependency. Keep every Univer package
      // on the same modern RxJS build; otherwise pnpm may resolve a nested
      // 7.0.0 copy whose ESM entry does not expose the operators Univer uses.
      dedupe: ['rxjs'],
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      // Worktrees may reuse installed dependencies through a junction. Allow only
      // that dependency directory, not its parent repository or private .env.
      fs: {
        allow: [
          fileURLToPath(new URL('.', import.meta.url)),
          realpathSync(fileURLToPath(new URL('./node_modules', import.meta.url))),
        ],
      },
      proxy: {
        '/agent': { target: agentTarget, ws: true, changeOrigin: false, timeout: 180000, proxyTimeout: 180000 },
        '/api': createDevProxy(apiTarget),
        '/actuator': createDevProxy(apiTarget),
        '/v3/api-docs': createDevProxy(apiTarget),
      },
    },
    build: {
      target: ['chrome106', 'edge106'],
      cssTarget: ['chrome106', 'edge106'],
      manifest: true,
      sourcemap: true,
    },
  };
});
