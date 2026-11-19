import { describe, it, expect } from 'vitest'
import { createTokens, TTL } from '../src/auth/tokens.js'

const SECRET = 'a'.repeat(40)
const ISSUER = 'https://review.example.com'
const T0 = 1_800_000_000_000

describe('tokens', () => {
  it('roundTrip', async () => {
    const t = createTokens(SECRET, ISSUER, () => T0)
    const token = await t.sign('access', { sub: '1', cid: 'client-a' })
    const claims = await t.verify('access', token)
    expect(claims).toMatchObject({ sub: '1', cid: 'client-a', typ: 'access', iss: ISSUER, iat: T0 / 1000 })
    expect(claims.exp).toBe(T0 / 1000 + TTL.access)
  })

  it('rejectsWrongKind', async () => {
    const t = createTokens(SECRET, ISSUER)
    const access = await t.sign('access', { sub: '1' })
    await expect(t.verify('refresh', access)).rejects.toThrow()
    // 호출자가 typ을 넣어도 종류는 바뀌지 않는다
    const sneaky = await t.sign('pending', { typ: 'access' })
    await expect(t.verify('access', sneaky)).rejects.toThrow()
  })

  it('rejectsExpired', async () => {
    let now = T0
    const t = createTokens(SECRET, ISSUER, () => now)
    const token = await t.sign('access', { sub: '1' })
    now = T0 + 3599 * 1000
    await expect(t.verify('access', token)).resolves.toMatchObject({ sub: '1' })
    now = T0 + 3601 * 1000
    await expect(t.verify('access', token)).rejects.toThrow()
  })

  it('clientNeverExpires', async () => {
    let now = T0
    const t = createTokens(SECRET, ISSUER, () => now)
    const token = await t.sign('client', { ruris: ['http://localhost:1/cb'] })
    now = T0 + 10 * 365 * 86400 * 1000
    const claims = await t.verify('client', token)
    expect(claims.exp).toBeUndefined()
  })

  it('rejectsTampered', async () => {
    const t = createTokens(SECRET, ISSUER)
    const token = await t.sign('access', { sub: '1' })
    const [h, p, s] = token.split('.')
    const bad = `${h}.${p}.${s[0] === 'A' ? 'B' : 'A'}${s.slice(1)}`
    await expect(t.verify('access', bad)).rejects.toThrow()
  })

  it('rejectsOtherSecret', async () => {
    const token = await createTokens(SECRET, ISSUER).sign('access', { sub: '1' })
    await expect(createTokens('b'.repeat(40), ISSUER).verify('access', token)).rejects.toThrow()
  })
})
