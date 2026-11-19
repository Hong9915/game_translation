import { createApp } from './app.js'
import { createBackendClient } from './backend.js'
import { loadConfig } from './config.js'

const cfg = loadConfig()
createApp(cfg, createBackendClient(cfg.backendUrl)).listen(cfg.port, () => {
  console.log(`mcp 서버가 ${cfg.port}번 포트에서 켜졌습니다.`)
})
