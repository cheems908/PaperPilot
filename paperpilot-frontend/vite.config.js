import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080'
  return {
    envDir: '..',
    plugins: [vue()],
    server: {
      proxy: Object.fromEntries(
        ['/api', '/actuator', '/health'].map(path => [
          path,
          { target: backend, changeOrigin: true }
        ])
      )
    }
  }
})
