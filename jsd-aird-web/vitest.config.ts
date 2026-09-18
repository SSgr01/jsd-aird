import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    // Ant Design's portal/table setup is intentionally exercised in the page
    // tests.  On a cold Windows CI worker those tests can take longer than
    // Vitest's five-second default even when the assertions are healthy.
    testTimeout: 60_000,
    hookTimeout: 60_000,
  },
});
