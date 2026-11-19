import { describe, it, expect } from 'vitest'
import request from 'supertest'
import { createHash, randomBytes } from 'node:crypto'
import type { Express } from 'express'
import { InvalidTokenError } from '@modelcontextprotocol/sdk/server/auth/errors.js'
import { createApp } from '../src/app.js'
import type { Config } from '../src/config.js'
import type { AccountInfo, BackendClient } from '../src/backend.js'
import { createTokens } from '../src/auth/tokens.js'
import { createClientsStore, isAllowedRedirect } from '../src/auth/clients.js'
import { createAccountChecker } from '../src/auth/accounts.js'
import { createProvider } from '../src/auth/provider.js'

const cfg: Config = {
  publicUrl: 'https://review.example.com',
  backendUrl: 'http://backend.invalid',
  tokenSecret: 's'.repeat(40),
  port: 0,
}
const HONG: AccountInfo = { accountId: 1, reviewer: { id: 7, name: '홍길동', color: '#f00' } }
const CALLBACK = 'http://localhost:33418/callback'

function fakeBackend(over: Partial<BackendClient> = {}): BackendClient {
  const unused = (): never => {
    throw new Error('이 시험에서 쓰지 않는다')
  }
  return {
    login: async (id, pw) => (id === 'hong' && pw === 'pw' ? HONG : null),
    account: async (id) => (id === 1 ? HONG : null),
    games: unused, project: unused, sheets: unused, rows: unused,
    saveBatch: unused, notes: unused, undo: unused,
    ...over,
  }
}

// 앱과 같은 비밀값으로 따로 만든 provider — 상태가 없으니 앱이 준 출입증을 그대로 확인한다
function providerFor(backend: BackendClient) {
  const tokens = createTokens(cfg.tokenSecret, cfg.publicUrl)
  return createProvider({
    tokens,
    clients: createClientsStore(tokens, cfg.tokenSecret),
    accounts: createAccountChecker(backend),
  })
}

function pkce() {
  const verifier = randomBytes(32).toString('base64url')
  const challenge = createHash('sha256').update(verifier).digest('base64url')
  return { verifier, challenge }
}

interface Registered { client_id: string; client_secret?: string; client_secret_expires_at?: number }

async function register(app: Express, extra: Record<string, unknown> = {}): Promise<Registered> {
  const res = await request(app)
    .post('/register')
    .send({ redirect_uris: [CALLBACK], token_endpoint_auth_method: 'none', client_name: 'Claude', ...extra })
  expect(res.status).toBe(201)
  return res.body as Registered
}

function authorize(app: Express, clientId: string, challenge: string, state = 'st-1') {
  return request(app).get('/authorize').query({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: CALLBACK,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  })
}

function pendingOf(html: string): string {
  const m = /name="pending" value="([^"]+)"/.exec(html)
  expect(m).not.toBeNull()
  return m![1]
}

function login(app: Express, pending: string, loginId = 'hong', password = 'pw') {
  return request(app).post('/oauth/login').type('form').send({ pending, loginId, password })
}

async function codeFor(app: Express, clientId: string, challenge: string): Promise<string> {
  const page = await authorize(app, clientId, challenge)
  expect(page.status).toBe(200)
  const res = await login(app, pendingOf(page.text))
  expect(res.status).toBe(302)
  return new URL(res.headers.location).searchParams.get('code')!
}

function exchange(app: Express, client: Registered, code: string, verifier: string) {
  return request(app).post('/token').type('form').send({
    grant_type: 'authorization_code',
    client_id: client.client_id,
    ...(client.client_secret ? { client_secret: client.client_secret } : {}),
    code,
    code_verifier: verifier,
    redirect_uri: CALLBACK,
  })
}

function refresh(app: Express, client: Registered, refreshToken: string) {
  return request(app).post('/token').type('form').send({
    grant_type: 'refresh_token',
    client_id: client.client_id,
    ...(client.client_secret ? { client_secret: client.client_secret } : {}),
    refresh_token: refreshToken,
  })
}

async function obtainTokens(app: Express, extra: Record<string, unknown> = {}) {
  const client = await register(app, extra)
  const { verifier, challenge } = pkce()
  const code = await codeFor(app, client.client_id, challenge)
  const res = await exchange(app, client, code, verifier)
  expect(res.status).toBe(200)
  return { client, access: res.body.access_token as string, refresh: res.body.refresh_token as string }
}

describe('OAuth flow', () => {
  it('metadataAdvertisesEndpoints', async () => {
    const app = createApp(cfg, fakeBackend())
    const as = await request(app).get('/.well-known/oauth-authorization-server')
    expect(as.status).toBe(200)
    expect(as.body.authorization_endpoint).toBe(`${cfg.publicUrl}/authorize`)
    expect(as.body.registration_endpoint).toBe(`${cfg.publicUrl}/register`)
    expect(as.body.token_endpoint).toBe(`${cfg.publicUrl}/token`)
    expect(as.body.revocation_endpoint).toBeUndefined()
    const pr = await request(app).get('/.well-known/oauth-protected-resource/mcp')
    expect(pr.status).toBe(200)
    expect(pr.body.resource).toBe(`${cfg.publicUrl}/mcp`)
    const health = await request(app).get('/health')
    expect(health.status).toBe(200)
    expect(health.text).toBe('ok')
  })

  it('allowedRedirects', () => {
    expect(isAllowedRedirect('https://claude.ai/api/mcp/auth_callback')).toBe(true)
    expect(isAllowedRedirect('https://claude.com/api/mcp/auth_callback')).toBe(true)
    expect(isAllowedRedirect('http://localhost:6274/oauth/callback')).toBe(true)
    expect(isAllowedRedirect('http://127.0.0.1:33418/callback')).toBe(true)
    expect(isAllowedRedirect('https://claude.ai/api/mcp/auth_callback/../x')).toBe(false)
    expect(isAllowedRedirect('https://claude.ai.evil.example/api/mcp/auth_callback')).toBe(false)
    expect(isAllowedRedirect('http://claude.ai/api/mcp/auth_callback')).toBe(false)
    expect(isAllowedRedirect('https://localhost:33418/callback')).toBe(false)
    expect(isAllowedRedirect('http://localhost.evil.example/cb')).toBe(false)
    expect(isAllowedRedirect('http://user@localhost:1/cb')).toBe(false)
    expect(isAllowedRedirect('not a url')).toBe(false)
  })

  it('registerRejectsForeignRedirect', async () => {
    const app = createApp(cfg, fakeBackend())
    const res = await request(app)
      .post('/register')
      .send({ redirect_uris: [CALLBACK, 'https://evil.example/cb'], token_endpoint_auth_method: 'none' })
    expect(res.status).toBe(400)
    expect(res.body.error).toBe('invalid_client_metadata')
    expect(res.body.client_id).toBeUndefined()
  })

  it('fullFlow', async () => {
    const backend = fakeBackend()
    const app = createApp(cfg, backend)
    const client = await register(app)
    expect(client.client_secret).toBeUndefined()
    const { verifier, challenge } = pkce()

    const page = await authorize(app, client.client_id, challenge, 'st-1')
    expect(page.status).toBe(200)
    expect(page.headers['content-type']).toMatch(/text\/html/)
    expect(page.text).toContain('name="pending"')
    expect(page.text).toContain('게임 번역 검수 — Claude 커넥터 로그인')
    expect(page.text).toContain('Claude이(가) 검수 도구에 접근하려고 합니다.')

    const done = await login(app, pendingOf(page.text))
    expect(done.status).toBe(302)
    const location = done.headers.location as string
    expect(location.startsWith(`${CALLBACK}?`)).toBe(true)
    const back = new URL(location)
    expect(back.searchParams.get('state')).toBe('st-1')
    const code = back.searchParams.get('code')
    expect(code).toBeTruthy()

    const tok = await exchange(app, client, code!, verifier)
    expect(tok.status).toBe(200)
    expect(tok.body).toMatchObject({ token_type: 'bearer', expires_in: 3600 })
    expect(tok.body.access_token).toBeTruthy()
    expect(tok.body.refresh_token).toBeTruthy()

    const info = await providerFor(backend).verifyAccessToken(tok.body.access_token)
    expect(info.clientId).toBe(client.client_id)
    expect(info.extra).toEqual({ accountId: 1, reviewerId: 7, reviewerName: '홍길동' })
    expect(info.expiresAt).toBeGreaterThan(Date.now() / 1000)

    const again = await refresh(app, client, tok.body.refresh_token)
    expect(again.status).toBe(200)
    expect(again.body.access_token).toBeTruthy()
    expect(again.body.refresh_token).toBeTruthy()
    expect((await providerFor(backend).verifyAccessToken(again.body.access_token)).extra?.reviewerId).toBe(7)
  })

  it('wrongPasswordRerendersWithoutRedirect', async () => {
    const app = createApp(cfg, fakeBackend())
    const client = await register(app)
    const page = await authorize(app, client.client_id, pkce().challenge)
    const pending = pendingOf(page.text)
    const res = await login(app, pending, 'hong', 'wrong-pw-123')
    expect(res.status).toBe(200)
    expect(res.text).toContain('아이디 또는 비밀번호가 틀렸습니다.')
    expect(res.text).not.toContain('wrong-pw-123')
    expect(res.headers.location).toBeUndefined()
    expect(pendingOf(res.text)).toBe(pending)
  })

  it('loginRejectsTamperedPending', async () => {
    const app = createApp(cfg, fakeBackend())
    const client = await register(app)
    const page = await authorize(app, client.client_id, pkce().challenge)
    const pending = pendingOf(page.text)
    // 다른 비밀값으로 서명한, 엉뚱한 곳으로 보내려는 pending
    const forged = await createTokens('x'.repeat(40), cfg.publicUrl).sign('pending', {
      cid: client.client_id, ruri: 'https://evil.example/cb', chal: 'c', state: 's',
    })
    const [h, p, s] = pending.split('.')
    for (const bad of [forged, `${h}.${p}.${s[0] === 'A' ? 'B' : 'A'}${s.slice(1)}`, 'garbage']) {
      const res = await login(app, bad)
      expect(res.status).toBe(400)
      expect(res.text).toContain('로그인 창이 만료됐습니다')
      expect(res.headers.location).toBeUndefined()
    }
  })

  it('loginPageEscapesClientName', async () => {
    const app = createApp(cfg, fakeBackend())
    const client = await register(app, { client_name: '<script>alert("x")</script>' })
    const page = await authorize(app, client.client_id, pkce().challenge)
    expect(page.status).toBe(200)
    expect(page.text).not.toContain('<script>')
    expect(page.text).toContain('&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;이(가)')
  })

  it('pkceMismatchFails', async () => {
    const app = createApp(cfg, fakeBackend())
    const client = await register(app)
    const { challenge } = pkce()
    const code = await codeFor(app, client.client_id, challenge)
    const res = await exchange(app, client, code, pkce().verifier)
    expect(res.status).toBe(400)
    expect(res.body.error).toBe('invalid_grant')
  })

  it('codeForOtherClientFails', async () => {
    const app = createApp(cfg, fakeBackend())
    const a = await register(app)
    const b = await register(app, { client_name: 'Other' })
    const { verifier, challenge } = pkce()
    const code = await codeFor(app, a.client_id, challenge)
    const res = await exchange(app, b, code, verifier)
    expect(res.status).toBe(400)
    expect(res.body.error).toBe('invalid_grant')
  })

  it('verifyRejectsWhenAccountInactive', async () => {
    const app = createApp(cfg, fakeBackend())
    const { access } = await obtainTokens(app)
    const inactive = providerFor(fakeBackend({ account: async () => null }))
    await expect(inactive.verifyAccessToken(access)).rejects.toBeInstanceOf(InvalidTokenError)
    await expect(inactive.verifyAccessToken('not-a-token')).rejects.toBeInstanceOf(InvalidTokenError)
  })

  it('accountCheckCachedForTtl', async () => {
    let now = 0
    let calls = 0
    const backend = fakeBackend({ account: async () => (calls++, HONG) })
    const checker = createAccountChecker(backend, 60_000, () => now)
    await checker.active(1)
    now = 59_999
    expect(await checker.active(1)).toEqual(HONG)
    expect(calls).toBe(1)
    now = 60_000
    await checker.active(1)
    expect(calls).toBe(2)
  })

  // RFC 7591: 방식을 빼면 client_secret_basic이 기본인데, SDK의 /token은 본문(client_secret_post)만 읽는다.
  // 그래서 빼고 등록하면 client_secret_post로 적어 돌려주고, 그 비밀값을 본문에 넣으면 끝까지 통해야 한다.
  it('registerWithoutAuthMethodUsesClientSecretPost', async () => {
    const app = createApp(cfg, fakeBackend())
    const reg = await request(app).post('/register').send({ redirect_uris: [CALLBACK], client_name: 'Claude' })
    expect(reg.status).toBe(201)
    expect(reg.body.token_endpoint_auth_method).toBe('client_secret_post')
    expect(reg.body.client_secret).toMatch(/^[0-9a-f]{64}$/)
    const client = reg.body as Registered
    const { verifier, challenge } = pkce()
    const code = await codeFor(app, client.client_id, challenge)
    const tok = await exchange(app, client, code, verifier)
    expect(tok.status).toBe(200)
    expect(tok.body.access_token).toBeTruthy()
    const again = await refresh(app, client, tok.body.refresh_token)
    expect(again.status).toBe(200)
  })

  // client_secret_basic(Authorization 헤더)은 SDK /token이 읽지 못한다 — 본문 방식으로 바꿔 적어 돌려준다.
  it('registerClientSecretBasicMapsToPost', async () => {
    const app = createApp(cfg, fakeBackend())
    const client = await register(app, { token_endpoint_auth_method: 'client_secret_basic' })
    expect((client as Registered & { token_endpoint_auth_method?: string }).token_endpoint_auth_method).toBe('client_secret_post')
    expect(client.client_secret).toMatch(/^[0-9a-f]{64}$/)
    const { verifier, challenge } = pkce()
    const code = await codeFor(app, client.client_id, challenge)
    expect((await exchange(app, client, code, verifier)).status).toBe(200)
  })

  it('survivesRestart', async () => {
    const backend = fakeBackend()
    const first = createApp(cfg, backend)
    const { client, refresh: refreshToken } = await obtainTokens(first, { token_endpoint_auth_method: 'client_secret_post' })
    expect(client.client_secret).toMatch(/^[0-9a-f]{64}$/)
    expect(client.client_secret_expires_at).toBe(0)

    const second = createApp(cfg, backend)
    const res = await refresh(second, client, refreshToken)
    expect(res.status).toBe(200)
    expect(res.body.access_token).toBeTruthy()
    const page = await authorize(second, client.client_id, pkce().challenge)
    expect(page.status).toBe(200)
    expect(page.text).toContain('name="pending"')
  })
})
