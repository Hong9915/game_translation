import { describe, it, expect } from 'vitest'
import { createBackendClient, BackendError } from '../src/backend.js'

interface Call { url: string; init?: RequestInit }

function fake(status: number, body: unknown) {
  const calls: Call[] = []
  const f = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init })
    return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
  }) as typeof fetch
  return { calls, client: createBackendClient('http://backend:8080', f) }
}

describe('BackendClient', () => {
  it('loginNullOn401', async () => {
    const { client, calls } = fake(401, { message: '아이디 또는 비밀번호가 올바르지 않습니다.' })
    expect(await client.login('a', 'b')).toBeNull()
    expect(calls[0].url).toBe('http://backend:8080/api/connector/login')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ loginId: 'a', password: 'b' })
  })

  it('loginReturnsAccount', async () => {
    const { client } = fake(200, { accountId: 3, disabled: false, reviewer: { id: 7, name: 'n', color: '#fff' } })
    expect(await client.login('a', 'b')).toEqual({ accountId: 3, reviewer: { id: 7, name: 'n', color: '#fff' } })
  })

  it('accountNullWhenDisabledOrNoReviewer', async () => {
    const r = { id: 7, name: 'n', color: '#fff' }
    expect(await fake(200, { accountId: 3, disabled: true, reviewer: r }).client.account(3)).toBeNull()
    expect(await fake(200, { accountId: 3, disabled: false, reviewer: null }).client.account(3)).toBeNull()
    expect(await fake(404, { message: 'x' }).client.account(3)).toBeNull()
    expect(await fake(200, { accountId: 3, disabled: false, reviewer: r }).client.account(3)).toEqual({ accountId: 3, reviewer: r })
  })

  it('rowsBuildsQuery', async () => {
    const { client, calls } = fake(200, { rows: [], page: 0, size: 50, total: 0 })
    await client.rows(9, { sheets: [12, 15], q: '가 나&다', qIn: 'SRC_CONTAINS', page: 0, size: 50 })
    const u = new URL(calls[0].url)
    expect(u.pathname).toBe('/api/games/9/review/rows')
    expect(u.searchParams.get('sheets')).toBe('12,15')
    expect(u.searchParams.get('q')).toBe('가 나&다')
    expect(u.searchParams.get('qIn')).toBe('SRC_CONTAINS')
    expect(u.searchParams.get('page')).toBe('0')
    expect(u.searchParams.get('size')).toBe('50')
    expect(u.searchParams.has('filter')).toBe(false)
  })

  it('saveSendsReviewerHeaderAndSource', async () => {
    const { client, calls } = fake(200, { saved: 1, skipped: 0, propagated: 0, activityIds: [5], skippedRows: [], finalMarked: 0 })
    const r = await client.saveBatch(7, [{ segmentId: 1, text: 'a', base: null }])
    expect(r.activityIds).toEqual([5])
    expect(calls[0].url).toBe('http://backend:8080/api/segments/save-batch')
    expect(calls[0].init!.method).toBe('POST')
    expect(new Headers(calls[0].init!.headers).get('X-Reviewer-Id')).toBe('7')
    const body = JSON.parse(calls[0].init!.body as string)
    expect(body.source).toBe('CONNECTOR')
    expect(body.items).toEqual([{ segmentId: 1, text: 'a', base: null }])
  })

  it('undoCallsConnectorUndoWithReviewer', async () => {
    const { client, calls } = fake(200, { reverted: 1, skipped: 0, activityIds: [9] })
    const r = await client.undo(7, [5, 6])
    expect(r).toEqual({ reverted: 1, skipped: 0, activityIds: [9] })
    expect(calls[0].url).toBe('http://backend:8080/api/connector/undo')
    expect(calls[0].init!.method).toBe('POST')
    expect(new Headers(calls[0].init!.headers).get('X-Reviewer-Id')).toBe('7')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ activityIds: [5, 6] })
  })

  it('proposePostsToConnectorProposals', async () => {
    const { client, calls } = fake(200, { proposalId: 3, presentNow: true, lockedRows: [] })
    const r = await client.propose(7, 't', [{ segmentId: 5, text: '가', base: null, reason: 'r' }])
    expect(r).toEqual({ proposalId: 3, presentNow: true, lockedRows: [] })
    expect(calls[0].url).toBe('http://backend:8080/api/connector/proposals')
    expect(calls[0].init!.method).toBe('POST')
    expect(new Headers(calls[0].init!.headers).get('X-Reviewer-Id')).toBe('7')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({
      title: 't',
      items: [{ segmentId: 5, text: '가', base: null, reason: 'r' }],
    })
  })

  it('proposalGetsById', async () => {
    const { client, calls } = fake(200, { id: 9, reviewerId: 7, status: 'PENDING', title: null, result: null })
    const r = await client.proposal(9)
    expect(r.id).toBe(9)
    expect(calls[0].url).toBe('http://backend:8080/api/connector/proposals/9')
    expect(calls[0].init?.method).toBeUndefined()
  })

  it('memoGlossaryCandidatesPaths', async () => {
    const { client, calls } = fake(200, { content: '', updatedAt: null })
    await client.memo(5)
    await client.glossary(5)
    await client.candidates(5)
    expect(calls.map((c) => c.url)).toEqual([
      'http://backend:8080/api/games/5/memo',
      'http://backend:8080/api/games/5/glossary?scope=ALL',
      'http://backend:8080/api/games/5/glossary/candidates',
    ])
  })

  it('replacePostsWithReviewerHeader', async () => {
    const { client, calls } = fake(200, { proposalId: 1 })
    await client.replace(7, { gameId: 5, find: 'a', replace: 'b' })
    expect(calls[0].url).toBe('http://backend:8080/api/connector/proposals/replace')
    expect((calls[0].init!.headers as Record<string, string>)['X-Reviewer-Id']).toBe('7')
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ gameId: 5, find: 'a', replace: 'b' })
  })

  it('sendsTextVerbatim', async () => {
    const { client, calls } = fake(200, { saved: 1, skipped: 0, propagated: 0, activityIds: [], skippedRows: [], finalMarked: 0 })
    const text = '<color=#FFD700>{0}</color>\n"인용"'
    await client.saveBatch(7, [{ segmentId: 1, text, base: null }])
    expect(JSON.parse(calls[0].init!.body as string).items[0].text).toBe(text)
  })

  it('non2xxThrowsBackendErrorWithMessage', async () => {
    const { client } = fake(400, { message: '한 번에 1~500행을 넣을 수 있습니다.' })
    const err = await client.saveBatch(7, []).catch((e) => e)
    expect(err).toBeInstanceOf(BackendError)
    expect(err.status).toBe(400)
    expect(err.message).toBe('한 번에 1~500행을 넣을 수 있습니다.')
  })

  it('fallbackMessageWhenNoBody', async () => {
    const f = (async () => new Response('oops', { status: 502 })) as typeof fetch
    const err = await createBackendClient('http://b', f).games().catch((e) => e)
    expect(err.message).toBe('검수 도구 서버 오류 (502)')
  })
  it('filesInspectNormalizeSheetsCallConnectorPaths', async () => {
    const f = fake(200, [])
    await f.client.files(5)
    expect(f.calls[0].url).toBe('http://backend:8080/api/connector/games/5/files')
    const i = fake(200, { sheets: [] })
    await i.client.inspect(3, 30, 30)
    expect(i.calls[0].url).toBe('http://backend:8080/api/connector/projects/3/inspect?offset=30&limit=30')
    const n = fake(200, { results: [] })
    await n.client.normalizeSheets(7, 3, { sheets: [{ sheetName: 'S', pairs: [{ srcCol: 'A' }] }] })
    expect(n.calls[0].url).toBe('http://backend:8080/api/connector/projects/3/normalize')
    expect(n.calls[0].init!.method).toBe('POST')
    expect((n.calls[0].init!.headers as Record<string, string>)['X-Reviewer-Id']).toBe('7')
    expect(JSON.parse(n.calls[0].init!.body as string)).toEqual({ sheets: [{ sheetName: 'S', pairs: [{ srcCol: 'A' }] }] })
  })
})
