import { SignJWT, jwtVerify } from 'jose'

// 서버는 아무것도 저장하지 않는다. 클라이언트 id·인가 코드·로그인 창 상태·출입증을 모두
// MCP_TOKEN_SECRET으로 서명한 JWT로 만들고, 'typ' 클레임으로 종류를 가른다.
export type TokenKind = 'access' | 'refresh' | 'code' | 'pending' | 'client'

/** 수명(초). client는 만료 없음. */
export const TTL = { access: 3600, refresh: 2592000, code: 300, pending: 600 } as const

export interface Tokens {
  sign(kind: TokenKind, claims: Record<string, unknown>): Promise<string>
  verify(kind: TokenKind, token: string): Promise<Record<string, unknown>>
}

export function createTokens(secret: string, issuer: string, now: () => number = Date.now): Tokens {
  const key = new TextEncoder().encode(secret)
  const seconds = () => Math.floor(now() / 1000)

  return {
    async sign(kind, claims) {
      const iat = seconds()
      // typ·iss·iat·exp는 호출자가 넣은 값보다 우선한다
      const jwt = new SignJWT({ ...claims, typ: kind })
        .setProtectedHeader({ alg: 'HS256' })
        .setIssuer(issuer)
        .setIssuedAt(iat)
      if (kind !== 'client') jwt.setExpirationTime(iat + TTL[kind])
      return jwt.sign(key)
    },

    async verify(kind, token) {
      const { payload } = await jwtVerify(token, key, {
        algorithms: ['HS256'],
        issuer,
        currentDate: new Date(now()),
        requiredClaims: kind === 'client' ? [] : ['exp'],
      })
      if (payload.typ !== kind) throw new Error('출입증 종류가 맞지 않습니다.')
      return payload as Record<string, unknown>
    },
  }
}
