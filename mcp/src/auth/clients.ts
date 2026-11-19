import { createHmac } from 'node:crypto'
import type { OAuthRegisteredClientsStore } from '@modelcontextprotocol/sdk/server/auth/clients.js'
import { InvalidClientMetadataError } from '@modelcontextprotocol/sdk/server/auth/errors.js'
import type { OAuthClientInformationFull } from '@modelcontextprotocol/sdk/shared/auth.js'
import type { Tokens } from './tokens.js'

const CLAUDE_CALLBACKS = new Set([
  'https://claude.ai/api/mcp/auth_callback',
  'https://claude.com/api/mcp/auth_callback',
])
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1'])

/** claude.ai·claude.com 콜백(정확히 같은 글자)과, 로컬 시험용 http://localhost·127.0.0.1(아무 포트)만 허용한다. */
export function isAllowedRedirect(uri: string): boolean {
  if (CLAUDE_CALLBACKS.has(uri)) return true
  let u: URL
  try {
    u = new URL(uri)
  } catch {
    return false
  }
  return u.protocol === 'http:' && LOOPBACK_HOSTS.has(u.hostname) && !u.username && !u.password && !u.hash
}

/**
 * 저장소 없는 동적 클라이언트 등록. client_id 자체가 { ruris, name, auth }를 담은 서명 출입증이라
 * 재시작해도 그대로 통한다. 비밀값은 client_id의 HMAC이라 언제든 다시 계산된다.
 */
export function createClientsStore(tokens: Tokens, secret: string): OAuthRegisteredClientsStore {
  const secretFor = (clientId: string) => createHmac('sha256', secret).update(clientId).digest('hex')

  async function getClient(clientId: string): Promise<OAuthClientInformationFull | undefined> {
    let claims: Record<string, unknown>
    try {
      claims = await tokens.verify('client', clientId)
    } catch {
      return undefined
    }
    const { ruris, name, auth, iat } = claims
    if (!Array.isArray(ruris) || ruris.length === 0) return undefined
    if (!ruris.every((r) => typeof r === 'string' && isAllowedRedirect(r))) return undefined
    const info: OAuthClientInformationFull = {
      client_id: clientId,
      client_id_issued_at: typeof iat === 'number' ? iat : undefined,
      redirect_uris: ruris as string[],
      token_endpoint_auth_method: typeof auth === 'string' ? auth : undefined,
      client_name: typeof name === 'string' ? name : undefined,
    }
    // SDK와 같게: 방식이 none일 때만 공개 클라이언트, 그 밖(생략 포함)은 비밀값을 쓴다
    if (info.token_endpoint_auth_method !== 'none') {
      info.client_secret = secretFor(clientId)
      info.client_secret_expires_at = 0
    }
    return info
  }

  return {
    getClient,
    async registerClient(meta) {
      const ruris = meta.redirect_uris
      if (ruris.length === 0 || !ruris.every(isAllowedRedirect)) {
        throw new InvalidClientMetadataError('허용되지 않은 redirect_uri입니다.')
      }
      // 방식을 빼면 RFC 7591 기본값은 client_secret_basic(Authorization 헤더)인데 SDK /token은 본문만 읽는다.
      // 그래서 생략·client_secret_basic은 client_secret_post로 적어 돌려준다 — 클라이언트는 돌려받은 방식을 따른다.
      const asked = meta.token_endpoint_auth_method
      const auth = asked === undefined || asked === 'client_secret_basic' ? 'client_secret_post' : asked
      const clientId = await tokens.sign('client', { ruris, name: meta.client_name, auth })
      const info = await getClient(clientId)
      if (!info) throw new Error('방금 만든 client_id를 확인하지 못했습니다.')
      // SDK가 만든 임의 비밀값·만료는 버리고, 우리 값(또는 없음)으로 덮는다
      const { client_secret: _s, client_secret_expires_at: _e, ...rest } = meta
      return { ...rest, ...info }
    },
  }
}
