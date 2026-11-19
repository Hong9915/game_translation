import express from 'express'
import type { BackendClient } from '../backend.js'
import { isAllowedRedirect } from './clients.js'
import type { Tokens } from './tokens.js'

const TITLE = '게임 번역 검수 — Claude 커넥터 로그인'
const WRONG = '아이디 또는 비밀번호가 틀렸습니다.'
const EXPIRED = '로그인 창이 만료됐습니다. Claude에서 다시 연결해 주세요.'
const BACKEND_DOWN = '검수 도구 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.'

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ESCAPES[c])

const CSS = `
:root { color-scheme: light dark; --bg: #f5f5f7; --card: #fff; --fg: #1d1d1f; --muted: #6e6e73; --line: #d2d2d7; --accent: #0071e3; --err: #d70015; }
@media (prefers-color-scheme: dark) { :root { --bg: #000; --card: #1c1c1e; --fg: #f5f5f7; --muted: #98989d; --line: #3a3a3c; --accent: #0a84ff; --err: #ff453a; } }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px; background: var(--bg); color: var(--fg);
  font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif; }
main { width: 100%; max-width: 360px; background: var(--card); border-radius: 14px; padding: 28px 24px; box-shadow: 0 1px 3px rgba(0,0,0,.12); }
h1 { font-size: 18px; margin: 0 0 20px; line-height: 1.35; }
label { display: block; font-size: 13px; color: var(--muted); margin: 12px 0 4px; }
input { width: 100%; font: inherit; padding: 9px 11px; border: 1px solid var(--line); border-radius: 8px; background: transparent; color: inherit; }
input:focus { outline: 2px solid var(--accent); outline-offset: -1px; border-color: transparent; }
button { width: 100%; margin-top: 20px; font: inherit; font-weight: 600; padding: 10px; border: 0; border-radius: 8px; background: var(--accent); color: #fff; cursor: pointer; }
.error { color: var(--err); margin: 12px 0 0; }
.note { color: var(--muted); font-size: 13px; margin: 16px 0 0; }
`

function page(body: string): string {
  return `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${TITLE}</title>
<style>${CSS}</style>
</head>
<body>
<main>
<h1>${TITLE}</h1>
${body}
</main>
</body>
</html>`
}

export function loginPage(o: { pending: string; clientName?: string; error?: string }): string {
  const who = o.clientName?.trim() || 'Claude'
  return page(`<form method="post" action="/oauth/login">
<input type="hidden" name="pending" value="${esc(o.pending)}">
<label for="loginId">아이디</label>
<input id="loginId" name="loginId" autocomplete="username" required autofocus>
<label for="password">비밀번호</label>
<input id="password" name="password" type="password" autocomplete="current-password" required>
${o.error ? `<p class="error" role="alert">${esc(o.error)}</p>` : ''}
<button type="submit">로그인</button>
</form>
<p class="note">${esc(who)}이(가) 검수 도구에 접근하려고 합니다.</p>`)
}

function messagePage(text: string): string {
  return page(`<p class="error" role="alert">${esc(text)}</p>`)
}

/** 로그인 화면용 HTML 응답 — 캐시·프레임 안에 넣기·외부 자원을 막는다. */
export function sendPage(res: express.Response, status: number, html: string): void {
  res
    .status(status)
    .set({
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Frame-Options': 'DENY',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
      'Referrer-Policy': 'no-referrer',
    })
    .send(html)
}

interface Pending { cid: string; ruri: string; chal: string; state?: string; res?: string; clientName?: string }

const optStr = (v: unknown) => v === undefined || typeof v === 'string'

/** 서명·만료·모양을 모두 확인한 pending만 돌려준다. 하나라도 어긋나면 null. */
async function readPending(tokens: Tokens, pending: string): Promise<Pending | null> {
  if (!pending) return null
  try {
    const p = await tokens.verify('pending', pending)
    const { cid, ruri, chal, state, res } = p
    if (typeof cid !== 'string' || typeof ruri !== 'string' || typeof chal !== 'string') return null
    if (!optStr(state) || !optStr(res) || !isAllowedRedirect(ruri)) return null
    // cid 자체가 서명된 클라이언트 출입증이다 — 다시 확인하면서 화면에 쓸 이름도 얻는다
    const client = await tokens.verify('client', cid)
    const clientName = typeof client.name === 'string' ? client.name : undefined
    return { cid, ruri, chal, state: state as string | undefined, res: res as string | undefined, clientName }
  } catch {
    return null
  }
}

const field = (body: Record<string, unknown>, name: string) => {
  const v = body[name]
  return typeof v === 'string' ? v : ''
}

export function loginRouter(deps: { tokens: Tokens; backend: BackendClient }): express.Router {
  const { tokens, backend } = deps
  const router = express.Router()

  router.post('/oauth/login', express.urlencoded({ extended: false, limit: '16kb' }), async (req, res) => {
    const body = (req.body ?? {}) as Record<string, unknown>
    const pending = field(body, 'pending')
    const p = await readPending(tokens, pending)
    // 검증되지 않은 pending으로는 어디로도 보내지 않는다
    if (!p) return sendPage(res, 400, messagePage(EXPIRED))

    const again = (status: number, error: string) =>
      sendPage(res, status, loginPage({ pending, clientName: p.clientName, error }))

    const loginId = field(body, 'loginId').trim()
    const password = field(body, 'password')
    if (!loginId || !password) return again(200, WRONG)

    let account
    try {
      account = await backend.login(loginId, password)
    } catch (e) {
      console.error('[oauth/login] 백엔드 로그인 확인 실패:', e instanceof Error ? e.message : String(e))
      return again(502, BACKEND_DOWN)
    }
    if (!account) return again(200, WRONG)

    const code = await tokens.sign('code', {
      sub: String(account.accountId),
      cid: p.cid,
      ruri: p.ruri,
      chal: p.chal,
      res: p.res,
    })
    const target = new URL(p.ruri)
    target.searchParams.set('code', code)
    if (p.state !== undefined) target.searchParams.set('state', p.state)
    res.set('Cache-Control', 'no-store').redirect(302, target.href)
  })

  return router
}
