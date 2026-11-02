import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// .claude/skills/local-preview 용: 5173의 프론트(compose 프론트 컨테이너 대신)가 8081의 최신 백엔드(로컬 DB 복사본)로 프록시한다.
// localhost의 큰 쿠키가 Tomcat 8KB 헤더 한도를 넘겨 400이 나므로 cookie를 뗀다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        configure: (proxy) => { proxy.on('proxyReq', (req) => { req.setHeader('origin', 'http://localhost:5173'); req.removeHeader('cookie') }) },
      },
    },
  },
})
