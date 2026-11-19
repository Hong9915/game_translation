import { describe, it, expect } from 'vitest'
import { BackendError } from '../src/backend.js'
import type { BackendClient, BackendRow, SheetInfo } from '../src/backend.js'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js'
import { findRows, proposalStatus, proposeRows, registerTools, saveRows, setNote } from '../src/tools.js'

function fake(over: Partial<BackendClient> = {}): BackendClient {
  const unused = (): never => {
    throw new Error('이 시험에서 쓰지 않는다')
  }
  return {
    login: unused, account: unused, games: unused, project: unused, sheets: unused, rows: unused,
    saveBatch: unused, notes: unused, undo: unused, propose: unused, proposal: unused,
    memo: unused, glossary: unused, candidates: unused, replace: unused,
    ...over,
  }
}

const FINAL: SheetInfo = { configId: 11, projectId: 1, fileName: 'a.xlsx', sheet: 'S1', finalReview: true }
const NORMAL: SheetInfo = { configId: 12, projectId: 2, fileName: 'b.xlsx', sheet: 'S2', finalReview: false }

const ROW: BackendRow = {
  id: 99, indexLabel: '3', src: '原', existingKo: '옛', fix: '수정',
  confirmed: false, needsCheck: true, finalMark: false, finalReview: false,
  note: '메모', dupCount: 2, projectId: 2, sheet: 'S2',
}

const parse = (r: { content: unknown }): any => JSON.parse((r.content as { text: string }[])[0].text)

describe('tools', () => {
  it('findRowsReportsBothLanes', async () => {
    const calls: number[][] = []
    const backend = fake({
      sheets: async () => [FINAL, NORMAL],
      rows: async (_g, q) => {
        calls.push(q.sheets)
        return q.sheets[0] === 12 ? { rows: [ROW], total: 1 } : { rows: [], total: 0 }
      },
    })
    const res = await findRows({ backend, reviewerId: 7 }, { gameId: 5 })
    const out = parse(res)
    expect(out.lanes).toHaveLength(2)
    expect(out.lanes[0]).toEqual({ lane: 'final', total: 0, rows: [] })
    expect(out.lanes[1].lane).toBe('normal')
    expect(out.lanes[1].rows[0]).toEqual({
      rowId: 99, file: 'b.xlsx', sheet: 'S2', index: '3', src: '原', existingKo: '옛', fix: '수정',
      confirmed: false, needsCheck: true, finalMark: false, note: '메모', dupCount: 2,
    })
    expect(calls).toEqual([[11], [12]])
  })

  it('findRowsSkipsLaneWithoutSheets', async () => {
    const backend = fake({ sheets: async () => [NORMAL], rows: async () => ({ rows: [], total: 0 }) })
    const out = parse(await findRows({ backend, reviewerId: 7 }, { gameId: 5 }))
    expect(out.lanes).toHaveLength(1)
    expect(out.lanes[0].lane).toBe('normal')
  })

  it('findRowsFromProjectId', async () => {
    let gameAsked = -1
    let sheetsSent: number[] = []
    const backend = fake({
      project: async (id) => ({ id, gameId: 5, fileName: 'b.xlsx' }),
      sheets: async (g) => {
        gameAsked = g
        return [FINAL, NORMAL]
      },
      rows: async (_g, q) => {
        sheetsSent = q.sheets
        return { rows: [], total: 0 }
      },
    })
    await findRows({ backend, reviewerId: 7 }, { projectId: 2 })
    expect(gameAsked).toBe(5)
    expect(sheetsSent).toEqual([12])
  })

  it('findRowsClampsSize', async () => {
    let size = 0
    const backend = fake({
      sheets: async () => [NORMAL],
      rows: async (_g, q) => {
        size = q.size
        return { rows: [], total: 0 }
      },
    })
    const out = parse(await findRows({ backend, reviewerId: 7 }, { gameId: 5, size: 500 }))
    expect(size).toBe(200)
    expect(out.size).toBe(200)
  })

  it('findRowsNeedsGameOrProject', async () => {
    const res = await findRows({ backend: fake(), reviewerId: 7 }, {})
    expect(res.isError).toBe(true)
  })

  it('saveRowsMapsIdsAndPassesReviewer', async () => {
    let seen: { reviewerId: number; items: unknown } | null = null
    const backend = fake({
      saveBatch: async (reviewerId, items) => {
        seen = { reviewerId, items }
        return { saved: 1, skipped: 1, unchanged: 2, propagated: 0, activityIds: [4], skippedRows: [{ id: 6, reason: '확정' }], finalMarked: 1 }
      },
    })
    const res = await saveRows(
      { backend, reviewerId: 7 },
      { items: [{ rowId: 5, text: '새', base: '옛' }] },
    )
    expect(seen).toEqual({ reviewerId: 7, items: [{ segmentId: 5, text: '새', base: '옛' }] })
    expect(parse(res)).toEqual({
      saved: 1, unchanged: 2, skipped: [{ rowId: 6, reason: '확정' }], propagated: 0, finalMarked: 1, activityIds: [4],
    })
  })

  it('setNoteMapsMode', async () => {
    let seen: unknown = null
    const backend = fake({
      notes: async (_r, items) => {
        seen = items
        return [{ segmentId: 5, before: null, after: 'x', needsCheck: false }]
      },
    })
    const res = await setNote({ backend, reviewerId: 7 }, { items: [{ rowId: 5, note: 'x', mode: 'append' }] })
    expect(seen).toEqual([{ segmentId: 5, note: 'x', needsCheck: undefined, mode: 'APPEND' }])
    expect(parse(res)).toEqual([{ rowId: 5, before: null, after: 'x', needsCheck: false }])
  })

  // 등록된 도구를 실제 MCP 클라이언트로 부른다 — 입력 스키마 검사까지 거친다
  async function callTool(backend: BackendClient, name: string, args: Record<string, unknown>) {
    const server = new McpServer({ name: 't', version: '1' })
    registerTools(server, { backend, reviewerId: 7 })
    const [a, b] = InMemoryTransport.createLinkedPair()
    const client = new Client({ name: 'c', version: '1' })
    await Promise.all([server.connect(a), client.connect(b)])
    try {
      return await client.callTool({ name, arguments: args })
    } finally {
      await client.close()
      await server.close()
    }
  }

  it('readGameMemoFromProjectId', async () => {
    const doc = { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '존댓말 사용' }] }] }
    const backend = fake({
      project: async () => ({ id: 3, gameId: 7, fileName: 'a.xlsx' }),
      memo: async (g) => {
        expect(g).toBe(7)
        return { content: JSON.stringify(doc), updatedAt: '2026-11-01T00:00:00Z' }
      },
    })
    const out = parse(await callTool(backend, 'read_game_memo', { projectId: 3 }))
    expect(out).toEqual({ gameId: 7, updatedAt: '2026-11-01T00:00:00Z', text: '존댓말 사용' })
  })

  it('readGameMemoNeedsGameOrProject', async () => {
    const res = await callTool(fake(), 'read_game_memo', {})
    expect(res.isError).toBe(true)
    expect((res.content as { text: string }[])[0].text).toBe('gameId 또는 projectId 중 하나는 꼭 넣어야 합니다.')
  })

  const entry = (find: string, ko: string | null = '값', note: string | null = null) =>
    ({ kind: 'SRC' as const, matchMode: 'WHOLE' as const, find, ko, note, shared: false })

  it('getGlossaryFiltersAndAddsPending', async () => {
    const backend = fake({
      glossary: async () => [entry('陷阱', '함정'), entry('关卡', '스테이지'), entry('金币', '골드', '재화')],
      candidates: async () => [
        { src: '陷阱', ko: '덫', oldKo: '함정', rows: 4, createdByName: '민', createdAt: '2026-11-18T01:00:00Z' },
      ],
    })
    const out = parse(await callTool(backend, 'get_glossary', { gameId: 5, q: '함정' }))
    expect(out.entries).toEqual([{ kind: 'SRC', matchMode: 'WHOLE', find: '陷阱', ko: '함정', note: null, shared: false }])
    expect(out.pending).toEqual([{ src: '陷阱', ko: '덫', oldKo: '함정', rows: 4, by: '민', at: '2026-11-18T01:00:00Z' }])
    expect(out.total).toBe(1)
    expect(out.truncated).toBe(false)
  })

  it('getGlossaryCandidatesFailureKeepsEntries', async () => {
    const backend = fake({
      glossary: async () => [entry('陷阱', '함정')],
      candidates: async () => {
        throw new Error('boom')
      },
    })
    const out = parse(await callTool(backend, 'get_glossary', { gameId: 5 }))
    expect(out.entries).toHaveLength(1)
    expect(out.pending).toEqual([])
  })

  it('getGlossaryFailureIsToolError', async () => {
    const backend = fake({
      glossary: async () => {
        throw new Error('boom')
      },
      candidates: async () => [],
    })
    const res = await callTool(backend, 'get_glossary', { gameId: 5 })
    expect(res.isError).toBe(true)
  })

  it('getGlossaryTruncatesAt500', async () => {
    const backend = fake({
      glossary: async () => Array.from({ length: 501 }, (_, i) => entry(`k${i}`)),
      candidates: async () => [],
    })
    const out = parse(await callTool(backend, 'get_glossary', { gameId: 5 }))
    expect(out.entries).toHaveLength(500)
    expect(out.truncated).toBe(true)
    expect(out.total).toBe(501)
  })

  it('listGamesDescriptionSaysUseAddressId', async () => {
    const server = new McpServer({ name: 't', version: '1' })
    registerTools(server, { backend: fake(), reviewerId: 7 })
    const [a, b] = InMemoryTransport.createLinkedPair()
    const client = new Client({ name: 'c', version: '1' })
    await Promise.all([server.connect(a), client.connect(b)])
    const tools = (await client.listTools()).tools
    await client.close()
    await server.close()
    expect(tools.find((x) => x.name === 'list_games')!.description).toContain('주소')
  })

  it('undoRejects51IdsWithoutCallingBackend', async () => {
    let called = false
    const backend = fake({
      undo: async () => {
        called = true
        return { reverted: 0, skipped: 0, activityIds: [] }
      },
    })
    const ids = Array.from({ length: 51 }, (_, i) => i + 1)
    const res = await callTool(backend, 'undo', { activityIds: ids })
    expect(res.isError).toBe(true)
    expect(called).toBe(false)

    const ok50 = await callTool(backend, 'undo', { activityIds: ids.slice(0, 50) })
    expect(ok50.isError).toBeFalsy()
    expect(called).toBe(true)
  })

  it('backendErrorBecomesToolError', async () => {
    const backend = fake({
      saveBatch: async () => {
        throw new BackendError(400, '한 번에 1~500행을 넣을 수 있습니다.')
      },
    })
    const res = await saveRows({ backend, reviewerId: 7 }, { items: [{ rowId: 1, text: 'a', base: null }] })
    expect(res.isError).toBe(true)
    expect((res.content as { text: string }[])[0].text).toBe('한 번에 1~500행을 넣을 수 있습니다.')
  })

  it('proposeRowsMapsRowIdAndReturnsLocked', async () => {
    let seen: { reviewerId: number; title: string | undefined; items: unknown } | null = null
    const backend = fake({
      propose: async (reviewerId, title, items) => {
        seen = { reviewerId, title, items }
        return { proposalId: 3, presentNow: false, lockedRows: [{ id: 5, reason: '확정' }] }
      },
    })
    const text = '<color=#FFD700>{0}</color>\n"가"'
    const res = await callTool(backend, 'propose_rows', {
      title: 't',
      items: [{ rowId: 5, text, base: '나', reason: 'r' }],
    })
    expect(seen).toEqual({
      reviewerId: 7,
      title: 't',
      items: [{ segmentId: 5, text, base: '나', reason: 'r' }],
    })
    expect(parse(res)).toEqual({ proposalId: 3, presentNow: false, locked: [{ rowId: 5, reason: '확정' }] })
  })

  it('proposeRowsRejects501WithoutCallingBackend', async () => {
    let called = false
    const backend = fake({
      propose: async () => {
        called = true
        return { proposalId: 1, presentNow: true, lockedRows: [] }
      },
    })
    const items = Array.from({ length: 501 }, (_, i) => ({ rowId: i + 1, text: 'a', base: null }))
    const res = await callTool(backend, 'propose_rows', { items })
    expect(res.isError).toBe(true)
    expect(called).toBe(false)
  })

  it('proposeReplacePassesBodyAndReviewer', async () => {
    let seen: { reviewerId: number; body: unknown } | null = null
    const backend = fake({
      replace: async (reviewerId, body) => {
        seen = { reviewerId, body }
        return {
          proposalId: 9, presentNow: true, matched: 4, proposed: 3, locked: 1, srcMissing: 0,
          samples: [{ rowId: 1, before: 'a', after: 'b' }], termCandidate: true,
        }
      },
    })
    const res = await callTool(backend, 'propose_replace', { projectId: 3, find: '함정', replace: '올가미', src: '陷阱' })
    expect(seen).toEqual({ reviewerId: 7, body: { projectId: 3, find: '함정', replace: '올가미', src: '陷阱' } })
    expect(parse(res)).toEqual({
      proposalId: 9, presentNow: true, matched: 4, proposed: 3, locked: 1, srcMissing: 0,
      samples: [{ rowId: 1, before: 'a', after: 'b' }], termCandidate: true,
    })
  })

  it('proposeReplaceNeedsGameOrProject', async () => {
    let called = false
    const backend = fake({
      replace: async () => {
        called = true
        throw new Error('x')
      },
    })
    const res = await callTool(backend, 'propose_replace', { find: 'a', replace: 'b' })
    expect(res.isError).toBe(true)
    expect(called).toBe(false)
  })

  it('findRowsTruncatesLongText', async () => {
    const long = '가'.repeat(301)
    const backend = fake({
      sheets: async () => [NORMAL],
      rows: async () => ({
        rows: [
          { ...ROW, fix: long },
          { ...ROW, id: 100 },
          { ...ROW, id: 101, src: long },
          { ...ROW, id: 102, note: long },
          { ...ROW, id: 103, src: '가'.repeat(300), fix: '나'.repeat(300) },
        ],
        total: 5,
      }),
    })
    const res = await callTool(backend, 'find_rows', { gameId: 1 })
    const rows = parse(res).lanes[0].rows
    expect(rows[0].fix).toBe(long)
    expect('truncated' in rows[0]).toBe(false)
    expect('truncated' in rows[1]).toBe(false)
    expect(rows[1].fix).toBe('수정')
    expect(rows[2].src).toBe('가'.repeat(300) + '…')
    expect(rows[2].truncated).toBe(true)
    expect(rows[3].note).toBe('가'.repeat(300) + '…')
    expect(rows[3].truncated).toBe(true)
    expect(rows[4].src).toBe('가'.repeat(300))
    expect('truncated' in rows[4]).toBe(false)
  })

  it('proposeRowsOmittedBaseAsksServer', async () => {
    let items: unknown = null
    const backend = fake({
      propose: async (_r, _t, i) => {
        items = i
        return { proposalId: 1, presentNow: true, lockedRows: [] }
      },
    })
    await callTool(backend, 'propose_rows', { items: [{ rowId: 1, text: 'a' }] })
    expect(items).toEqual([{ segmentId: 1, text: 'a', base: null, autoBase: true }])
  })

  it('proposeRowsNullBaseStaysNull', async () => {
    let items: unknown = null
    const backend = fake({
      propose: async (_r, _t, i) => {
        items = i
        return { proposalId: 1, presentNow: true, lockedRows: [] }
      },
    })
    await callTool(backend, 'propose_rows', { items: [{ rowId: 1, text: 'a', base: null }] })
    expect(items).toEqual([{ segmentId: 1, text: 'a', base: null, reason: undefined }])
    expect('autoBase' in (items as object[])[0]).toBe(false)
  })

  it('proposalStatusMapsResult', async () => {
    const backend = fake({
      proposal: async (id) => ({
        id,
        reviewerId: 7,
        status: 'APPLIED',
        title: '제목',
        result: {
          saved: 2, unchanged: 1, propagated: 3, excluded: [11, 12],
          skippedRows: [{ id: 13, reason: '확정' }], finalMarked: 1, activityIds: [40, 41],
        },
      }),
    })
    expect(parse(await callTool(backend, 'proposal_status', { proposalId: 9 }))).toEqual({
      status: 'APPLIED', title: '제목', saved: 2, unchanged: 1, propagated: 3,
      excluded: [11, 12], skipped: [{ rowId: 13, reason: '확정' }], finalMarked: 1, activityIds: [40, 41],
    })

    const pending = fake({
      proposal: async (id) => ({ id, reviewerId: 7, status: 'PENDING', title: null, result: null }),
    })
    expect(parse(await callTool(pending, 'proposal_status', { proposalId: 9 }))).toEqual({
      status: 'PENDING', title: null, saved: 0, unchanged: 0, propagated: 0,
      excluded: [], skipped: [], finalMarked: 0, activityIds: [],
    })
  })

  it('proposalStatusRejectsOtherAccount', async () => {
    const backend = fake({
      proposal: async (id) => ({ id, reviewerId: 8, status: 'PENDING', title: null, result: null }),
    })
    const res = await callTool(backend, 'proposal_status', { proposalId: 9 })
    expect(res.isError).toBe(true)
    expect((res.content as { text: string }[])[0].text).toBe('다른 계정의 제안입니다.')
  })

  it('proposalBackendErrorBecomesToolError', async () => {
    const backend = fake({
      proposal: async () => {
        throw new BackendError(404, '제안을 찾을 수 없습니다: 9')
      },
    })
    const res = await callTool(backend, 'proposal_status', { proposalId: 9 })
    expect(res.isError).toBe(true)
    expect((res.content as { text: string }[])[0].text).toBe('제안을 찾을 수 없습니다: 9')
  })
})
