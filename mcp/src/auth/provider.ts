import type { OAuthRegisteredClientsStore } from '@modelcontextprotocol/sdk/server/auth/clients.js'
import { InvalidGrantError, InvalidTokenError } from '@modelcontextprotocol/sdk/server/auth/errors.js'
import type { OAuthServerProvider } from '@modelcontextprotocol/sdk/server/auth/provider.js'
import type { AuthInfo } from '@modelcontextprotocol/sdk/server/auth/types.js'
import type { OAuthClientInformationFull, OAuthTokens } from '@modelcontextprotocol/sdk/shared/auth.js'
import type { AccountChecker } from './accounts.js'
import { loginPage, sendPage } from './login.js'
import { TTL, type Tokens } from './tokens.js'

/** sub 클레임 → 계정 id. 양의 정수가 아니면 null. */
function accountIdOf(claims: Record<string, unknown>): number | null {
  const id = Number(claims.sub)
  return typeof claims.sub === 'string' && Number.isSafeInteger(id) && id > 0 ? id : null
}

const resOf = (claims: Record<string, unknown>) => (typeof claims.res === 'string' ? claims.res : undefined)

export function createProvider(deps: {
  tokens: Tokens
  clients: OAuthRegisteredClientsStore
  accounts: AccountChecker
}): OAuthServerProvider {
  const { tokens, clients, accounts } = deps

  async function issue(clientId: string, accountId: number, res: string | undefined): Promise<OAuthTokens> {
    const claims = { sub: String(accountId), cid: clientId, res }
    return {
      access_token: await tokens.sign('access', claims),
      token_type: 'bearer',
      expires_in: TTL.access,
      refresh_token: await tokens.sign('refresh', claims),
    }
  }

  /** 서명·만료·종류를 확인하고, 요청한 클라이언트에게 준 코드인지 본다. */
  async function readCode(client: OAuthClientInformationFull, code: string) {
    let c: Record<string, unknown>
    try {
      c = await tokens.verify('code', code)
    } catch {
      throw new InvalidGrantError('인가 코드가 올바르지 않거나 만료됐습니다.')
    }
    const accountId = accountIdOf(c)
    if (c.cid !== client.client_id || accountId === null || typeof c.chal !== 'string' || typeof c.ruri !== 'string') {
      throw new InvalidGrantError('이 클라이언트에 발급한 인가 코드가 아닙니다.')
    }
    return { accountId, chal: c.chal, ruri: c.ruri, res: resOf(c) }
  }

  async function requireActive(accountId: number): Promise<void> {
    if (!(await accounts.active(accountId))) throw new InvalidGrantError('쓸 수 없는 계정입니다.')
  }

  return {
    clientsStore: clients,

    async authorize(client, params, res) {
      const pending = await tokens.sign('pending', {
        cid: client.client_id,
        ruri: params.redirectUri,
        chal: params.codeChallenge,
        state: params.state,
        res: params.resource?.href,
        scopes: params.scopes,
      })
      sendPage(res, 200, loginPage({ pending, clientName: client.client_name }))
    },

    async challengeForAuthorizationCode(client, code) {
      return (await readCode(client, code)).chal
    },

    async exchangeAuthorizationCode(client, code, _codeVerifier, redirectUri) {
      const c = await readCode(client, code)
      if (redirectUri !== undefined && redirectUri !== c.ruri) {
        throw new InvalidGrantError('redirect_uri가 인가 요청과 다릅니다.')
      }
      await requireActive(c.accountId)
      return issue(client.client_id, c.accountId, c.res)
    },

    async exchangeRefreshToken(client, refreshToken) {
      let c: Record<string, unknown>
      try {
        c = await tokens.verify('refresh', refreshToken)
      } catch {
        throw new InvalidGrantError('갱신 출입증이 올바르지 않거나 만료됐습니다.')
      }
      const accountId = accountIdOf(c)
      if (c.cid !== client.client_id || accountId === null) {
        throw new InvalidGrantError('이 클라이언트에 발급한 갱신 출입증이 아닙니다.')
      }
      await requireActive(accountId)
      return issue(client.client_id, accountId, resOf(c))
    },

    // InvalidTokenError 메시지는 SDK가 WWW-Authenticate 헤더에 그대로 넣는다. 헤더는 Latin-1만 되므로(한글이면
    // Node가 거부해 401 대신 500이 되고 클라이언트가 갱신하지 않는다) 여기 메시지는 꼭 ASCII로 둔다.
    async verifyAccessToken(token): Promise<AuthInfo> {
      let c: Record<string, unknown>
      try {
        c = await tokens.verify('access', token)
      } catch {
        throw new InvalidTokenError('invalid or expired token')
      }
      const accountId = accountIdOf(c)
      if (accountId === null || typeof c.cid !== 'string' || typeof c.exp !== 'number') {
        throw new InvalidTokenError('invalid token')
      }
      const account = await accounts.active(accountId)
      if (!account) throw new InvalidTokenError('account disabled')
      const res = resOf(c)
      return {
        token,
        clientId: c.cid,
        scopes: [],
        expiresAt: c.exp,
        resource: res ? new URL(res) : undefined,
        extra: { accountId, reviewerId: account.reviewer.id, reviewerName: account.reviewer.name },
      }
    },
  }
}
