/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// In development, /api/** is forwarded to the Spring backend so the browser sees one origin.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.BACKEND_URL || 'http://localhost:8080',
          changeOrigin: true,
          // Same as the production Node server: the backend must not see the browser's Origin,
          // otherwise its CORS check rejects POSTs when Vite runs on a port it doesn't list.
          configure: (proxy) => proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin')),
        },
      },
    },
    test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test-setup.ts'] },
  }
})
