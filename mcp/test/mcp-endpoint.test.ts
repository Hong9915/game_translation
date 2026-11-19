import { describe, it, expect, vi } from 'vitest'
import request from 'supertest'
import { createHash, randomBytes } from 'node:crypto'
import type { Express } from 'express'
import { createApp } from '../src/app.js'
import type { Config } from '../src/config.js'
import type { AccountInfo, BackendClient } from '../src/backend.js'
import { createTokens } from '../src/auth/tokens.js'

const cfg: Config = {
  publicUrl: 'https://review.example.com',
  backendUrl: 'http://backend.invalid',
  tokenSecret: 's'.repeat(40),
  port: 0,
}
const HONG: AccountInfo = { accountId: 1, reviewer: { id: 7, name: '홍길동', color: '#f00' } }
const CALLBACK = 'http://localhost:33418/callback'
const MCP_HEADERS = { Accept: 'application/json, text/event-stream', 'Content-Type': 'application/json' }

function fakeBackend(): BackendClient {
  const unused = (): never => {
    throw new Error('이 시험에서 쓰지 않는다')
  }
  return {
    login: async (id, pw) => (id === 'hong' && pw === 'pw' ? HONG : null),
    account: async (id) => (id === 1 ? HONG : null),
    games: unused, project: unused, sheets: unused, rows: unused,
    saveBatch: unused, notes: unused, undo: unused, propose: unused, proposal: unused,
    memo: unused, glossary: unused, candidates: unused, replace: unused,
  }
}

async function accessToken(app: Express): Promise<string> {
  const reg = await request(app)
    .post('/register')
    .send({ redirect_uris: [CALLBACK], token_endpoint_auth_method: 'none', client_name: 'Claude' })
  const clientId = reg.body.client_id as string
  const verifier = randomBytes(32).toString('base64url')
  const challenge = createHash('sha256').update(verifier).digest('base64url')
  const page = await request(app).get('/authorize').query({
    response_type: 'code', client_id: clientId, redirect_uri: CALLBACK,
    code_challenge: challenge, code_challenge_method: 'S256', state: 's',
  })
  const pending = /name="pending" value="([^"]+)"/.exec(page.text)![1]
  const done = await request(app).post('/oauth/login').type('form').send({ pending, loginId: 'hong', password: 'pw' })
  const code = new URL(done.headers.location).searchParams.get('code')!
  const tok = await request(app).post('/token').type('form').send({
    grant_type: 'authorization_code', client_id: clientId, code, code_verifier: verifier, redirect_uri: CALLBACK,
  })
  return tok.body.access_token as string
}

// 응답은 JSON 또는 SSE(event: message / data: {...}) — 둘 다 풀어 준다
function rpcBody(res: request.Response): any {
  if (res.text) {
    const line = res.text.split('\n').find((l) => l.startsWith('data:'))
    if (line) return JSON.parse(line.slice(5).trim())
    return JSON.parse(res.text)
  }
  return res.body
}

describe('/mcp', () => {
  it('rejectsWithoutToken', async () => {
    const app = createApp(cfg, fakeBackend())
    const res = await request(app).post('/mcp').set(MCP_HEADERS).send({ jsonrpc: '2.0', id: 1, method: 'tools/list' })
    expect(res.status).toBe(401)
    expect(res.headers['www-authenticate']).toContain('resource_metadata=')
  })

  // 출입증을 거절할 때 메시지가 WWW-Authenticate 헤더에 들어간다 — 한글이면 Node가 헤더를 거부해 500이 된다.
  // 클라이언트는 401을 받아야만 갱신 토큰으로 다시 받으므로, 꼭 401 + invalid_token + resource_metadata여야 한다.
  async function expectInvalidToken(token: string) {
    const app = createApp(cfg, fakeBackend())
    const res = await request(app)
      .post('/mcp')
      .set({ ...MCP_HEADERS, Authorization: `Bearer ${token}` })
      .send({ jsonrpc: '2.0', id: 1, method: 'tools/list' })
    expect(res.status).toBe(401)
    expect(res.headers['www-authenticate']).toContain('invalid_token')
    expect(res.headers['www-authenticate']).toContain('resource_metadata=')
  }

  it('garbageTokenGets401', async () => {
    await expectInvalidToken('garbage')
  })

  it('expiredTokenGets401', async () => {
    // 같은 비밀값으로 두 시간 전에 서명한 출입증 — 수명(1시간)이 이미 지났다
    const past = createTokens(cfg.tokenSecret, cfg.publicUrl, () => Date.now() - 2 * 3600 * 1000)
    await expectInvalidToken(await past.sign('access', { sub: '1', cid: 'c' }))
  })

  it('inactiveAccountTokenGets401', async () => {
    // 서명은 맞지만 계정 2는 막혔다(가짜 백엔드가 null)
    const now = createTokens(cfg.tokenSecret, cfg.publicUrl)
    await expectInvalidToken(await now.sign('access', { sub: '2', cid: 'c' }))
  })

  it('logs401WithoutToken', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const app = createApp(cfg, fakeBackend())
      const secret = 'garbage-token-must-not-be-logged'
      const res = await request(app)
        .post('/mcp')
        .set({ ...MCP_HEADERS, Authorization: `Bearer ${secret}` })
        .send({ jsonrpc: '2.0', id: 1, method: 'tools/list' })
      expect(res.status).toBe(401)
      const lines = warn.mock.calls.map((c) => c.map(String).join(' '))
      expect(lines.some((l) => l.includes('POST /mcp 401'))).toBe(true)
      expect(lines.join('\n')).not.toContain(secret)
    } finally {
      warn.mockRestore()
    }
  })

  it('logsFailedRegisterWithMethodAndHost', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const app = createApp(cfg, fakeBackend())
      const res = await request(app)
        .post('/register')
        .send({ redirect_uris: ['https://evil.example/cb?x=secret-q'], token_endpoint_auth_method: 'none', client_name: 'secret-name' })
      expect(res.status).toBe(400)
      const lines = warn.mock.calls.map((c) => c.map(String).join(' ')).join('\n')
      expect(lines).toContain('POST /register 400')
      expect(lines).toContain('auth=none')
      expect(lines).toContain('redirect=evil.example')
      expect(lines).not.toContain('secret-q')
      expect(lines).not.toContain('secret-name')
    } finally {
      warn.mockRestore()
    }
  })

  it('listsTenTools', async () => {
    const app = createApp(cfg, fakeBackend())
    const token = await accessToken(app)
    const auth = { ...MCP_HEADERS, Authorization: `Bearer ${token}` }
    const init = await request(app).post('/mcp').set(auth).send({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 't', version: '1' } },
    })
    expect(init.status).toBe(200)
    const list = await request(app).post('/mcp').set(auth).send({ jsonrpc: '2.0', id: 2, method: 'tools/list' })
    expect(list.status).toBe(200)
    const tools = rpcBody(list).result.tools as { name: string; annotations?: { readOnlyHint?: boolean } }[]
    expect(tools.map((t) => t.name).sort()).toEqual(['find_rows', 'get_glossary', 'list_games', 'proposal_status', 'propose_replace', 'propose_rows', 'read_game_memo', 'save_rows', 'set_note', 'undo'])
    expect(tools.find((t) => t.name === 'find_rows')!.annotations?.readOnlyHint).toBe(true)
  })

  it('getIs405', async () => {
    const app = createApp(cfg, fakeBackend())
    const token = await accessToken(app)
    const res = await request(app).get('/mcp').set('Authorization', `Bearer ${token}`)
    expect(res.status).toBe(405)
  })
})
