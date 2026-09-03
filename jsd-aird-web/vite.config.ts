import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
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
        '/api': apiTarget,
        '/actuator': apiTarget,
        '/v3/api-docs': apiTarget,
      },
    },
    build: {
      sourcemap: true,
    },
  };
});
