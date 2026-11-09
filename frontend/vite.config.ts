import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const target = process.env.API_PROXY_TARGET || (mode === 'demo' ? 'http://127.0.0.1:4187' : 'http://127.0.0.1:8080');
  const proxy = {
    '/api': {
      target,
      changeOrigin: false,
    },
  };
  return {
    plugins: [react()],
    server: { host: '127.0.0.1', proxy },
    preview: { host: '127.0.0.1', proxy },
    test: {
      clearMocks: true,
      restoreMocks: true,
      projects: [
        { test: { name: 'ui', include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'], environment: 'jsdom', setupFiles: ['./tests/setup.ts'] } },
        { test: { name: 'http', include: ['tests/**/*.test.mjs'], environment: 'node' } },
      ],
    },
  };
});
