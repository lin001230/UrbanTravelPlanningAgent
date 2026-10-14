import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/api': {
      target: env.API_PROXY_TARGET || 'http://localhost:8888',
      changeOrigin: true,
      timeout: 330000,
      proxyTimeout: 330000,
    },
  }
  return {
    plugins: [vue()],
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
  }
})
