import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Demo Shop Frontend가 5173을 쓰므로 PulseOps는 5174로 분리한다. 관측 대상과 관측 화면은
    // 개발 중에 항상 함께 떠 있어야 하고, 두 Dev Server가 같은 Port를 기본값으로 두면 나중에 뜬
    // 쪽이 조용히 다른 Port로 밀린다. strictPort는 그때 밀리는 대신 실패하게 한다.
    port: 5174,
    strictPort: true,
    // PulseOps Backend는 8081에서 별도로 실행된다. /api를 Proxy하면 브라우저가 단일 Origin을
    // 유지하므로 상대 경로로 호출할 수 있고 Backend에 CORS 설정이 필요 없다.
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
