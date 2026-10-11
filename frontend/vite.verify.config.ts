import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
// 확인용: 백엔드 CORS는 5173만 허용하므로 프록시가 Origin을 5173으로 바꿔 보낸다.
export default defineConfig({ plugins: [react()], server: { proxy: { '/api': { target: 'http://localhost:8080',
  // 앱은 쿠키를 쓰지 않는다. localhost의 다른 앱이 남긴 큰 쿠키가 Tomcat 헤더 한도(8KB)를 넘겨 400이 나므로 떼어 보낸다.
  configure: (proxy) => { proxy.on('proxyReq', (req) => { req.setHeader('origin', 'http://localhost:5173'); req.removeHeader('cookie') }) } } } } })
