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
        '/api': { target: env.BACKEND_URL || 'http://localhost:8080', changeOrigin: true },
      },
    },
    test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test-setup.ts'] },
  }
})
