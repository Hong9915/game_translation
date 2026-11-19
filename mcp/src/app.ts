import express from 'express'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { requireBearerAuth } from '@modelcontextprotocol/sdk/server/auth/middleware/bearerAuth.js'
import { getOAuthProtectedResourceMetadataUrl, mcpAuthRouter } from '@modelcontextprotocol/sdk/server/auth/router.js'
import type { BackendClient } from './backend.js'
import type { Config } from './config.js'
import { createAccountChecker } from './auth/accounts.js'
import { createClientsStore } from './auth/clients.js'
import { loginRouter } from './auth/login.js'
import { createProvider } from './auth/provider.js'
import { createTokens } from './auth/tokens.js'
import { registerTools } from './tools.js'

/** 운영 기록을 남기는 경로. 첫 실제 연결이 운영에서 일어나므로 실패가 흔적 없이 사라지지 않게 한다. */
const LOGGED_PATHS = new Set(['/register', '/token', '/authorize', '/oauth/login', '/mcp'])

/** 클라이언트가 보낸 값을 한 줄 기록에 안전하게 — 줄바꿈 등은 '?'로, 길이는 40자까지. */
const safe = (v: unknown) => String(v).replace(/[^\w.:-]/g, '?').slice(0, 40)

const hostOf = (u: unknown) => {
  try {
    return safe(new URL(String(u)).host)
  } catch {
    return '?'
  }
}

/** /register 실패 때 원인 짐작용: 요청한 인증 방식과 redirect 주소의 호스트만(경로·쿼리·이름은 남기지 않는다). */
function registerHint(body: unknown): string {
  const b = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>
  const method = b.token_endpoint_auth_method === undefined ? '(생략)' : safe(b.token_endpoint_auth_method)
  const hosts = Array.isArray(b.redirect_uris) ? b.redirect_uris.map(hostOf).join(',') : '?'
  return ` auth=${method} redirect=${hosts}`
}

/**
 * 위 경로의 2xx 아닌 응답마다 한 줄(메서드·경로·상태)을 남긴다. 본문·출입증·코드·비밀번호·비밀값·쿼리는 남기지 않는다.
 * 경로는 맨 앞 미들웨어에서 읽는다 — SDK 라우터 안에서는 req.path가 잘려 있다.
 */
function logFailures(): express.RequestHandler {
  return (req, res, next) => {
    const path = req.path
    if (LOGGED_PATHS.has(path)) {
      res.on('finish', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) return
        const hint = path === '/register' ? registerHint(req.body) : ''
        console.warn(`[http] ${req.method} ${path} ${res.statusCode}${hint}`)
      })
    }
    next()
  }
}

export function createApp(cfg: Config, backend: BackendClient): express.Express {
  const tokens = createTokens(cfg.tokenSecret, cfg.publicUrl)
  const clients = createClientsStore(tokens, cfg.tokenSecret)
  const provider = createProvider({ tokens, clients, accounts: createAccountChecker(backend) })

  const app = express()
  app.set('trust proxy', 1) // nginx 한 단 뒤 — 요청 수 제한이 실제 접속 주소를 쓰게
  app.use(logFailures())

  // SDK 고정 경로: /authorize, /token, /register, /.well-known/oauth-* (/revoke는 만들지 않는다)
  app.use(
    mcpAuthRouter({
      provider,
      issuerUrl: new URL(cfg.publicUrl),
      resourceServerUrl: new URL('/mcp', cfg.publicUrl),
      resourceName: '게임 번역 검수',
      clientRegistrationOptions: { clientIdGeneration: false },
    }),
  )
  app.use(loginRouter({ tokens, backend }))

  // 요청마다 서버·전송을 새로 만드는 상태 없는 방식 — reviewerId는 출입증에서만 온다
  const bearer = requireBearerAuth({
    verifier: provider,
    resourceMetadataUrl: getOAuthProtectedResourceMetadataUrl(new URL('/mcp', cfg.publicUrl)),
  })
  app.post('/mcp', bearer, express.json({ limit: '4mb' }), async (req, res) => {
    const server = new McpServer({ name: 'game-translation-review', version: '1.0.0' })
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
    res.on('close', () => {
      void transport.close()
      void server.close()
    })
    try {
      registerTools(server, { backend, reviewerId: Number(req.auth!.extra!.reviewerId) })
      await server.connect(transport)
      await transport.handleRequest(req, res, req.body)
    } catch (e) {
      console.error('[mcp] /mcp 처리 중 오류:', e instanceof Error ? (e.stack ?? e.message) : String(e))
      if (!res.headersSent) {
        res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: '서버 오류' }, id: null })
      }
    }
  })
  const notAllowed: express.RequestHandler = (_req, res) => {
    res.set('Allow', 'POST').status(405).json({
      jsonrpc: '2.0',
      error: { code: -32000, message: 'POST만 쓸 수 있습니다.' },
      id: null,
    })
  }
  app.get('/mcp', notAllowed)
  app.delete('/mcp', notAllowed)
  app.get('/health', (_req, res) => {
    res.type('text/plain').send('ok')
  })
  return app
}
