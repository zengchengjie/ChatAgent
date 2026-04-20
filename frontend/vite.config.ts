import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Use localhost to match browser loopback resolution (may be IPv6 ::1 on macOS)
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
