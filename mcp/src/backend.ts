export interface Reviewer { id: number; name: string; color: string }
export interface AccountInfo { accountId: number; reviewer: Reviewer }
export interface SheetInfo { configId: number; projectId: number; fileName: string; sheet: string; finalReview: boolean }
export interface RowQuery {
  sheets: number[]
  q?: string
  qIn?: 'DEFAULT' | 'EXISTING_KO' | 'SRC_EXACT' | 'SRC_CONTAINS'
  filter?: string
  page: number
  size: number
}
export interface BackendRow {
  id: number; indexLabel: string; src: string; existingKo: string | null; fix: string | null
  confirmed: boolean; needsCheck: boolean; finalMark: boolean; finalReview: boolean
  note: string | null; dupCount: number; projectId: number; sheet: string
}
export interface SaveItem { segmentId: number; text: string; base: string | null }
/** unchanged: 이미 같은 글이라 넣지도 건너뛰지도 않은 행 수. */
export interface SaveResult {
  saved: number; skipped: number; unchanged: number; propagated: number; activityIds: number[]
  skippedRows: { id: number; reason: string }[]; finalMarked: number
}
export interface NoteItem { segmentId: number; note?: string; needsCheck?: boolean; mode?: 'APPEND' | 'REPLACE' }
export interface NoteResult { segmentId: number; before: string | null; after: string | null; needsCheck: boolean }
export interface UndoResult { reverted: number; skipped: number; activityIds: number[] }
export interface ProposeItem { segmentId: number; text: string; base: string | null; reason?: string; autoBase?: boolean }
export interface ProposeResult { proposalId: number; presentNow: boolean; lockedRows: { id: number; reason: string }[] }
export interface ProposalInfo {
  id: number
  reviewerId: number
  status: 'PENDING' | 'APPLIED' | 'DISMISSED' | 'EXPIRED'
  title: string | null
  result: {
    saved: number; unchanged: number; propagated: number; excluded: number[]
    skippedRows: { id: number; reason: string }[]; finalMarked: number; activityIds: number[]
  } | null
}

export interface GlossaryEntry {
  kind: 'SRC' | 'KO'; matchMode: 'WHOLE' | 'PARTIAL'; find: string
  ko: string | null; note: string | null; shared: boolean
}
export interface Candidate {
  src: string; ko: string; oldKo: string | null; rows: number; createdByName: string | null; createdAt: string
}
export interface ReplaceBody {
  gameId?: number; projectId?: number; sheet?: string; find: string; replace: string
  src?: string; reason?: string; title?: string; exceptRowIds?: number[]
}
export interface ReplaceResultBody {
  proposalId: number; presentNow: boolean; matched: number; proposed: number; locked: number; srcMissing: number
  samples: { rowId: number; before: string; after: string }[]
  termCandidate: boolean
}

export class BackendError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'BackendError'
  }
}

export interface BackendClient {
  login(loginId: string, password: string): Promise<AccountInfo | null>
  account(accountId: number): Promise<AccountInfo | null>
  games(): Promise<{ id: number; name: string }[]>
  project(projectId: number): Promise<{ id: number; gameId: number; fileName: string }>
  sheets(gameId: number): Promise<SheetInfo[]>
  rows(gameId: number, q: RowQuery): Promise<{ rows: BackendRow[]; total: number }>
  saveBatch(reviewerId: number, items: SaveItem[]): Promise<SaveResult>
  notes(reviewerId: number, items: NoteItem[]): Promise<NoteResult[]>
  undo(reviewerId: number, activityIds: number[]): Promise<UndoResult>
  propose(reviewerId: number, title: string | undefined, items: ProposeItem[]): Promise<ProposeResult>
  proposal(id: number): Promise<ProposalInfo>
  memo(gameId: number): Promise<{ content: string; updatedAt: string | null }>
  glossary(gameId: number): Promise<GlossaryEntry[]>
  candidates(gameId: number): Promise<Candidate[]>
  replace(reviewerId: number, body: ReplaceBody): Promise<ReplaceResultBody>
}

interface AccountBody { accountId: number; disabled: boolean; reviewer: Reviewer | null }

export function createBackendClient(baseUrl: string, fetchImpl: typeof fetch = fetch): BackendClient {
  const base = baseUrl.replace(/\/+$/, '')

  async function fail(res: Response): Promise<never> {
    let message = `검수 도구 서버 오류 (${res.status})`
    try {
      const body = (await res.json()) as { message?: unknown }
      if (typeof body?.message === 'string' && body.message) message = body.message
    } catch {
      // 본문이 JSON이 아니면 기본 문구
    }
    throw new BackendError(res.status, message)
  }

  async function call(path: string, init?: RequestInit, tolerate: number[] = []): Promise<Response> {
    const res = await fetchImpl(base + path, init)
    if (!res.ok && !tolerate.includes(res.status)) await fail(res)
    return res
  }

  const get = async <T>(path: string): Promise<T> => (await call(path)).json() as Promise<T>

  const post = async <T>(path: string, body: unknown, reviewerId?: number): Promise<T> => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (reviewerId !== undefined) headers['X-Reviewer-Id'] = String(reviewerId)
    const res = await call(path, { method: 'POST', headers, body: JSON.stringify(body) })
    return res.json() as Promise<T>
  }

  const toAccount = (b: AccountBody): AccountInfo | null =>
    b.disabled || !b.reviewer ? null : { accountId: b.accountId, reviewer: b.reviewer }

  return {
    async login(loginId, password) {
      const res = await call(
        '/api/connector/login',
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ loginId, password }) },
        [401],
      )
      if (res.status === 401) return null
      return toAccount((await res.json()) as AccountBody)
    },
    async account(accountId) {
      const res = await call(`/api/connector/accounts/${accountId}`, undefined, [404])
      if (res.status === 404) return null
      return toAccount((await res.json()) as AccountBody)
    },
    games: () => get('/api/games'),
    project: (id) => get(`/api/projects/${id}`),
    sheets: (gameId) => get(`/api/connector/games/${gameId}/sheets`),
    async rows(gameId, q) {
      const p = new URLSearchParams()
      p.set('sheets', q.sheets.join(','))
      if (q.filter) p.set('filter', q.filter)
      if (q.q) p.set('q', q.q)
      if (q.qIn) p.set('qIn', q.qIn)
      p.set('page', String(q.page))
      p.set('size', String(q.size))
      const r = await get<{ rows: BackendRow[]; total: number }>(`/api/games/${gameId}/review/rows?${p}`)
      return { rows: r.rows, total: r.total }
    },
    saveBatch: (reviewerId, items) =>
      post('/api/segments/save-batch', { items, source: 'CONNECTOR' }, reviewerId),
    notes: (reviewerId, items) => post('/api/connector/notes', { items }, reviewerId),
    // 커넥터 전용 되돌리기 — 그 검수자가 커넥터로 고친 것(과 그 되돌림·다시 실행)만 받는다
    undo: (reviewerId, activityIds) => post('/api/connector/undo', { activityIds }, reviewerId),
    propose: (reviewerId, title, items) => post('/api/connector/proposals', { title, items }, reviewerId),
    proposal: (id) => get(`/api/connector/proposals/${id}`),
    memo: (gameId) => get(`/api/games/${gameId}/memo`),
    glossary: (gameId) => get(`/api/games/${gameId}/glossary?scope=ALL`),
    candidates: (gameId) => get(`/api/games/${gameId}/glossary/candidates`),
    replace: (reviewerId, body) => post('/api/connector/proposals/replace', body, reviewerId),
  }
}
