import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 백엔드는 8080 포트에서 별도로 실행된다. /api를 Proxy하면 브라우저가 단일 Origin을
    // 유지하므로 상대 경로로 호출할 수 있고 CORS 설정이 필요 없다.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
