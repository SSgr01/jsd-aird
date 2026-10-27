import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';
import { buildInfoPlugin } from './scripts/build-info-plugin';
import { createDevProxy } from './scripts/dev-proxy';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080';

  return {
    plugins: [react(), buildInfoPlugin()],
    resolve: {
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
      proxy: {
        '/api': createDevProxy(apiTarget),
        '/actuator': createDevProxy(apiTarget),
        '/v3/api-docs': createDevProxy(apiTarget),
      },
    },
    build: {
      manifest: true,
      sourcemap: true,
    },
  };
});
