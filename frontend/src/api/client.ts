import type {
  AssignRequest, ExportPreview, Game, NormalizeRequest, NormalizeResponse, PresenceEntry, Project,
  MarkKey, MarksPatch, QuickTranslateRequest, QuickTranslateResult, ReviewerRef, RowFilter, RowShortenResult, SearchIn, WorkspaceRow, WorkspaceRowsResponse, WorkspaceSummary,
  SaveResult, SegmentRow, SheetsResponse, UsageSummary,
  GlossaryEntry, GlossaryPhraseCategory, GlossaryRecommendation, GlossaryRequest, GlossarySaveResult, GlossarySuggestResult, GlossaryVariant, GlossaryUnifyRequest, GlossarySpacingItem, TagSpacingItem, TagSpacingRow, CommandPlan, OpSpec, CommandPreset, RowHistoryEntry,
  TermApproveItem, TermApproveResult, TermRequestDetail, TermRequestSummary,
  ActivityFeedResponse, ActivityRevertResult, ActivityRowsResponse, JosaKind, JosaPreview, JobDto, TranslateStartRequest,
} from './types'
import { loadReviewer } from '../lib/reviewer'
import type { ReviewTarget } from '../lib/reviewTarget'

export const API_BASE: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (init.body && !(init.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const me = loadReviewer()
  if (me) headers['X-Reviewer-Id'] = String(me.id)
  // 호출자가 직접 준 헤더(예: presence.leave의 고정 reviewer id)가 자동으로 붙는 값을 덮어쓴다.
  Object.assign(headers, init.headers as Record<string, string> | undefined)
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try { const j = await res.json(); if (j && typeof j.message === 'string') message = j.message } catch { /* ignore */ }
    throw new ApiError(res.status, message)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** attachment; filename="..."; filename*=UTF-8''... — 한글 이름은 filename*에만 제대로 있다. */
export function dispositionName(header: string | null): string | null {
  if (!header) return null
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (star) { try { return decodeURIComponent(star[1].trim()) } catch { /* 아래로 */ } }
  const plain = /filename="([^"]*)"/i.exec(header)
  return plain && !plain[1].startsWith('=?') ? plain[1] : null
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })
const qs = (params: Record<string, string | number | boolean | undefined>): string => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined) p.set(k, String(v))
  const s = p.toString()
  return s ? `?${s}` : ''
}

/** 기본이 아닌 칸에서 찾을 때만 qIn을 보낸다(검색어가 없으면 뜻이 없다). 기본은 보내지 않는다. */
const searchIn = (q: string | undefined, qIn: SearchIn | undefined): SearchIn | undefined =>
  q && qIn && qIn !== 'DEFAULT' ? qIn : undefined

/**
 * 범위(파일 화면·게임 화면)에 걸리는 리뷰 API(설계 2026-10-12). 파일 화면은 지금 경로를 그대로 쓴다(api.workspace와 같은 URL).
 * 게임 화면은 /games/{gameId}/review로 가고 범위는 쿼리 sheets=로, 본문에는 sheet를 싣지 않는다.
 */
function reviewApi(t: ReviewTarget) {
  const game = t.kind === 'game'
  const base = game ? `/games/${t.gameId}/review` : `/review/${t.projectId}`
  const scope: Record<string, string | undefined> = game ? { sheets: t.sheetIds.length ? t.sheetIds.join(',') : undefined } : { sheet: t.sheet }
  // POST: 파일 화면은 본문의 sheet로, 게임 화면은 쿼리의 sheets로 범위를 준다
  const postQs = game ? qs(scope) : ''
  const sheetBody = game ? {} : { sheet: t.sheet }
  return {
    rows: (p: { page?: number; size?: number; filter?: RowFilter; q?: string; qIn?: SearchIn }) =>
      request<WorkspaceRowsResponse>(`${base}/rows${qs({ ...scope, page: p.page ?? 0, size: p.size ?? 500, filter: p.filter === 'ALL' ? undefined : p.filter, q: p.q || undefined, qIn: searchIn(p.q, p.qIn) })}`),
    bulkMarks: (body: { filter?: RowFilter; q?: string; qIn?: SearchIn; ids?: number[] } & Partial<Record<MarkKey, boolean>>) =>
      request<{ updated: number }>(`${base}/marks${postQs}`, { method: 'POST', ...json({ ...sheetBody, ...body, filter: body.filter === 'ALL' ? undefined : body.filter, q: body.q || undefined, qIn: searchIn(body.q, body.qIn) }) }),
    recomputeTermMiss: () =>
      request<{ count: number }>(`${base}/term-miss/recompute${postQs}`, { method: 'POST', ...json(sheetBody) }),
    summary: () => request<WorkspaceSummary>(`${base}/rows/summary${qs(scope)}`),
    tagSpacing: () => request<TagSpacingRow[]>(`${base}/tag-spacing${qs(scope)}`),
    fixTagSpacing: (items: TagSpacingItem[]) =>
      request<{ applied: number }>(`${base}/tag-spacing${postQs}`, { method: 'POST', ...json({ items }) }),
    commandPlan: (body: { command?: string; ops?: OpSpec[]; check?: 'TAG_MISMATCH' | 'OVER_LENGTH'; only?: number[]; ratio?: number }) =>
      request<CommandPlan>(`${base}/command-fix/plan${postQs}`, { method: 'POST', ...json({ ...sheetBody, ...body }) }),
    fixCommand: (body: { command: string; ops: OpSpec[]; items: TagSpacingItem[] }) =>
      request<{ applied: number }>(`${base}/command-fix${postQs}`, { method: 'POST', ...json({ ...sheetBody, ...body }) }),
    // 저장 명령은 게임 단위라 범위를 싣지 않는다
    commandPresets: () => request<CommandPreset[]>(`${base}/command-presets`),
    saveCommandPreset: (body: { name: string; ops: OpSpec[] }) =>
      request<CommandPreset>(`${base}/command-presets`, { method: 'POST', ...json(body) }),
    deleteCommandPreset: (presetId: number) => request<void>(`${base}/command-presets/${presetId}`, { method: 'DELETE' }),
    josaPreview: () => request<JosaPreview>(`${base}/josa/preview${qs(scope)}`),
    josaApply: (body: { segmentIds?: number[]; kinds: JosaKind[] }) =>
      request<{ changed: number; skipped: number }>(`${base}/josa/apply${postQs}`, { method: 'POST', ...json(body.segmentIds ? body : { ...sheetBody, ...body }) }),
    josaIgnore: (segmentId: number) => request<void>(`${base}/josa/ignore`, { method: 'POST', ...json({ segmentId }) }),
  }
}

export const api = {
  // 전체 누적 LLM 사용량. 게임 목록 상단에 한 줄로 보여 준다.
  usageAll: () => request<UsageSummary>('/usage'),
  games: {
    list: () => request<Game[]>('/games'),
    get: (id: number) => request<Game>(`/games/${id}`),
    create: (name: string) => request<Game>('/games', { method: 'POST', ...json({ name }) }),
    rename: (id: number, name: string) => request<Game>(`/games/${id}`, { method: 'PATCH', ...json({ name }) }),
    delete: (id: number) => request<void>(`/games/${id}`, { method: 'DELETE' }),
    setDeepLInstructions: (id: number, text: string) => request<Game>(`/games/${id}/deepl-instructions`, { method: 'PUT', ...json({ text }) }),
    quickTranslate: (id: number, body: QuickTranslateRequest) => request<QuickTranslateResult>(`/games/${id}/quick-translate`, { method: 'POST', ...json(body) }),
  },
  projects: {
    list: (gameId?: number) => request<Project[]>(`/projects${qs({ gameId })}`),
    get: (id: number) => request<Project>(`/projects/${id}`),
    delete: (id: number) => request<void>(`/projects/${id}`, { method: 'DELETE' }),
    upload: (file: File, gameId: number) => {
      const fd = new FormData()
      fd.append('file', file)
      fd.append('gameId', String(gameId))
      return request<Project>('/projects', { method: 'POST', body: fd })
    },
    patch: (id: number, body: { gameId?: number; currentStep?: number }) =>
      request<Project>(`/projects/${id}`, { method: 'PATCH', ...json(body) }),
  },
  /** 단어장 요청: 개발사가 채운 양식을 올려 번역본 표기와 대조하고, 승인한 용어를 단어장에 넣는다. */
  termRequests: {
    upload: (gameId: number, file: File) => {
      const fd = new FormData()
      fd.append('file', file)
      return request<TermRequestDetail>(`/games/${gameId}/glossary/requests`, { method: 'POST', body: fd })
    },
    list: (gameId: number) => request<TermRequestSummary[]>(`/games/${gameId}/glossary/requests`),
    get: (id: number) => request<TermRequestDetail>(`/glossary/requests/${id}`),
    generate: (id: number) => request<TermRequestDetail>(`/glossary/requests/${id}/generate`, { method: 'POST' }),
    approve: (id: number, items: TermApproveItem[]) =>
      request<TermApproveResult>(`/glossary/requests/${id}/approve`, { method: 'POST', ...json({ items }) }),
  },
  sheets: (id: number, headerRow: number) => request<SheetsResponse>(`/projects/${id}/sheets${qs({ headerRow })}`),
  normalize: (id: number, body: NormalizeRequest) => request<NormalizeResponse>(`/projects/${id}/normalize`, { method: 'PUT', ...json(body) }),
  /** 정규화한 시트의 빈 행을 DeepL로 번역하는 작업을 건다. 진행은 프로젝트 스트림(activity)으로 온다. */
  startTranslate: (id: number, body: TranslateStartRequest) => request<JobDto>(`/projects/${id}/translate`, { method: 'POST', ...json(body) }),
  normalizeCurrent: (id: number, opts: { sheet?: string; page?: number; size?: number } = {}) =>
    request<NormalizeResponse>(`/projects/${id}/normalize${qs({ sheet: opts.sheet, page: opts.page ?? 0, size: opts.size ?? 50 })}`),
  // 시트 이름에 %·#·공백·한글이 들어갈 수 있어 경로가 아니라 쿼리 파라미터로 보낸다(qs가 인코딩한다).
  removeSheet: (id: number, sheetName: string) => request<void>(`/projects/${id}/normalize${qs({ sheet: sheetName })}`, { method: 'DELETE' }),
  saveTranslation: (segmentId: number, text: string, ratio?: number) =>
    request<SaveResult>(`/segments/${segmentId}/translation${qs({ ratio })}`, { method: 'PUT', ...json({ text }) }),
  reviewers: {
    list: () => request<ReviewerRef[]>('/reviewers'),
    // 이름을 추가하면 바로 등록된다. 같은 이름이 있으면 그 검토자가 돌아온다.
    upsert: (name: string) => request<ReviewerRef>('/reviewers', { method: 'POST', ...json({ name }) }),
    // 오타로 등록된 이름을 바꾼다. 다른 검토자가 이미 쓰는 이름이면 서버가 400을 준다.
    rename: (id: number, name: string) => request<ReviewerRef>(`/reviewers/${id}`, { method: 'PATCH', ...json({ name }) }),
    // 검토자를 지운다. transferTo를 주면 그 사람의 담당·완료·수정 기록을 옮기고, 없으면 비운다.
    remove: (id: number, transferTo?: number) => request<void>(`/reviewers/${id}${qs({ transferTo })}`, { method: 'DELETE' }),
  },
  assign: (id: number, req: AssignRequest) => request<{ updated: number }>(`/projects/${id}/assign`, { method: 'POST', ...json(req) }),
  // 검수 그리드의 행 하나를 GPT로 번역해 그 행의 최신 한국어(Fix)로 쌓는다. 같은 원문 행에는 퍼지지 않는다(사람이 저장할 때 퍼진다).
  // 행 하나를 DeepL로 번역한다. 원문 언어는 서버가 그 파일에서 마지막으로 고른 값을 쓴다.
  translateRow: (segmentId: number) => request<WorkspaceRow>(`/segments/${segmentId}/translate`, { method: 'POST', ...json({}) }),
  // 행의 지금 한국어를 GPT로 줄인 후보만 받는다(저장 안 함). maxChars가 없으면 서버가 원문 글자 수 × ratio로 정한다.
  shortenRow: (segmentId: number, opts: { maxChars?: number; ratio?: number } = {}) =>
    request<RowShortenResult>(`/segments/${segmentId}/shorten`, { method: 'POST', ...json(opts) }),
  // 검수 체크·비고. 준 칸만 바뀌고 그리드 한 행이 돌아온다.
  setMarks: (segmentId: number, patch: MarksPatch) =>
    request<WorkspaceRow>(`/segments/${segmentId}/marks`, { method: 'PUT', ...json(patch) }),
  setDone: (segmentId: number, done: boolean, ratio?: number) =>
    request<SegmentRow>(`/segments/${segmentId}/done${qs({ ratio })}`, { method: 'PUT', ...json({ done }) }),
  // 검수 워크스페이스: 시트 한 페이지를 DB에서 잘라 받는다(옛 segments API처럼 프로젝트 전 행을 올리지 않는다).
  review: reviewApi,
  workspace: {
    rows: (id: number, p: { sheet: string; page?: number; size?: number; filter?: RowFilter; q?: string; qIn?: SearchIn }) =>
      request<WorkspaceRowsResponse>(`/review/${id}/rows${qs({ sheet: p.sheet, page: p.page ?? 0, size: p.size ?? 500, filter: p.filter === 'ALL' ? undefined : p.filter, q: p.q || undefined, qIn: searchIn(p.q, p.qIn) })}`),
    summary: (id: number, sheet?: string) => request<WorkspaceSummary>(`/review/${id}/rows/summary${qs({ sheet })}`),
    // 머리글 일괄 체크: ids가 있으면 그 행만(쪽 보기의 화면 행), 없으면 목록과 같은 조건(시트·필터·검색)의 행 전부를 체크·해제한다.
    bulkMarks: (id: number, body: { sheet: string; filter?: RowFilter; q?: string; qIn?: SearchIn; ids?: number[] } & Partial<Record<MarkKey, boolean>>) =>
      request<{ updated: number }>(`/review/${id}/marks`, { method: 'POST', ...json({ ...body, filter: body.filter === 'ALL' ? undefined : body.filter, q: body.q || undefined, qIn: searchIn(body.q, body.qIn) }) }),
    // 시트 하나의 용어 불일치를 지금 단어장으로 다시 계산한다. count는 표시가 바뀐 행 수.
    recomputeTermMiss: (id: number, sheet: string) =>
      request<{ count: number }>(`/review/${id}/term-miss/recompute`, { method: 'POST', ...json({ sheet }) }),
    // 조사 제안(설계 2026-10-07 §5). preview는 시트의 종류별 건수·예시, apply는 ids가 있으면 그 행만, 없으면 시트의 조사 확인 대상 전부.
    // 행 하나의 변경 내역, 최신 판부터(우클릭 "변경 내역 보기")
    rowHistory: (id: number, segmentId: number) => request<RowHistoryEntry[]>(`/review/${id}/rows/${segmentId}/history`),
    // 태그 경계 띄어쓰기(2026-10-08): 시트의 후보 행(Kiwi가 태그 경계를 띄우는 행)과 고른 행 고치기
    tagSpacing: (id: number, sheet: string) => request<TagSpacingRow[]>(`/review/${id}/tag-spacing${qs({ sheet })}`),
    fixTagSpacing: (id: number, items: TagSpacingItem[]) =>
      request<{ applied: number }>(`/review/${id}/tag-spacing`, { method: 'POST', ...json({ items }) }),
    commandPlan: (id: number, body: { sheet: string; command?: string; ops?: OpSpec[]; check?: 'TAG_MISMATCH' | 'OVER_LENGTH'; only?: number[]; ratio?: number }) =>
      request<CommandPlan>(`/review/${id}/command-fix/plan`, { method: 'POST', ...json(body) }),
    fixCommand: (id: number, body: { sheet: string; command: string; ops: OpSpec[]; items: TagSpacingItem[] }) =>
      request<{ applied: number }>(`/review/${id}/command-fix`, { method: 'POST', ...json(body) }),
    commandPresets: (id: number) => request<CommandPreset[]>(`/review/${id}/command-presets`),
    saveCommandPreset: (id: number, body: { name: string; ops: OpSpec[] }) =>
      request<CommandPreset>(`/review/${id}/command-presets`, { method: 'POST', ...json(body) }),
    deleteCommandPreset: (id: number, presetId: number) =>
      request<void>(`/review/${id}/command-presets/${presetId}`, { method: 'DELETE' }),
    josaPreview: (id: number, sheet: string) => request<JosaPreview>(`/review/${id}/josa/preview${qs({ sheet })}`),
    josaApply: (id: number, body: { segmentIds?: number[]; sheet?: string; kinds: JosaKind[] }) =>
      request<{ changed: number; skipped: number }>(`/review/${id}/josa/apply`, { method: 'POST', ...json(body) }),
    josaIgnore: (id: number, segmentId: number) =>
      request<void>(`/review/${id}/josa/ignore`, { method: 'POST', ...json({ segmentId }) }),
  },
  presence: {
    // 게임 전체 리뷰 화면의 접속자는 파일 화면과 따로 센다
    touchGame: (gameId: number, body: { sheet?: string | null; editingSegmentId?: number | null }) =>
      request<{ reviewers: PresenceEntry[] }>(`/games/${gameId}/review/presence`, { method: 'POST', ...json(body) }),
    leaveGame: (gameId: number, reviewerId: number) =>
      request<void>(`/games/${gameId}/review/presence`, { method: 'DELETE', keepalive: true, headers: { 'X-Reviewer-Id': String(reviewerId) } }),
    touch: (id: number, body: { sheet?: string | null; editingSegmentId?: number | null }) =>
      request<{ reviewers: PresenceEntry[] }>(`/projects/${id}/presence`, { method: 'POST', ...json(body) }),
    // 화면을 떠나는 중에도 요청이 끝까지 가도록 keepalive. reviewerId를 명시해야 한다 — 이 사이에
    // 검토자가 바뀌었으면(예: 이름 전환) request()가 자동으로 붙이는 헤더는 이미 새 id를 가리킨다.
    leave: (id: number, reviewerId: number) =>
      request<void>(`/projects/${id}/presence`, { method: 'DELETE', keepalive: true, headers: { 'X-Reviewer-Id': String(reviewerId) } }),
  },
  // 다운로드는 브라우저가 직접 열게 URL만 준다
  urls: {
    original: (id: number) => `${API_BASE}/projects/${id}/export/original`,
  },
  // single이면 시트 하나만 내보낼 때의 미리보기(집계·출력 이름이 그 시트만)
  exportPreview: (id: number, ratio: number, sheet?: string, single?: boolean) =>
    request<ExportPreview>(`/projects/${id}/export/preview${qs({ ratio, sheet, single: single || undefined })}`),
  // sheet를 주면 그 시트 하나만 든 파일(설계 2026-10-12 §4). overwriteKo면 새 Fix 열 대신 기존 한국어 열에 덮어쓴다(이번 파일만).
  // finalOnly면 최종 수정 표시를 켠 행만 원본과 같은 열로(2026-10-19). sheet와 같이 주면 그 시트만.
  exportXlsx: async (id: number, opts: { addFlagColumn: boolean; addHistorySheet: boolean; addGlossarySheet: boolean; ratio: number; sheet?: string; overwriteKo?: boolean; finalOnly?: boolean }): Promise<Blob> => {
    const res = await fetch(`${API_BASE}/projects/${id}/export`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(opts),
    })
    if (!res.ok) throw new ApiError(res.status, '내보내기에 실패했습니다.')
    return res.blob()
  },
  /** 게임 전체 zip용: 서버가 붙인 파일 이름(N차)과 실패 사유까지 받는다. */
  exportFile: async (id: number, opts: { addFlagColumn: boolean; addHistorySheet: boolean; addGlossarySheet: boolean; ratio: number; overwriteKo?: boolean; addNoteColumn?: boolean }): Promise<{ blob: Blob; name: string | null }> => {
    const res = await fetch(`${API_BASE}/projects/${id}/export`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(opts),
    })
    if (!res.ok) {
      let message = '내보내기에 실패했습니다.'
      try { const j = await res.json(); if (j && typeof j.message === 'string') message = j.message } catch { /* ignore */ }
      throw new ApiError(res.status, message)
    }
    return { blob: await res.blob(), name: dispositionName(res.headers.get('Content-Disposition')) }
  },
  activity: {
    // 최신순 커서 페이지. before는 그보다 작은 id만.
    feed: (projectId: number, before?: number, size?: number) =>
      request<ActivityFeedResponse>(`/projects/${projectId}/activity${qs({ before, size })}`),
    // 게임 전체 리뷰 화면: 범위에 든 파일들 + 게임 단위 항목
    gameFeed: (gameId: number, sheetIds: number[], before?: number, size?: number) =>
      request<ActivityFeedResponse>(`/games/${gameId}/review/activity${qs({ sheets: sheetIds.length ? sheetIds.join(',') : undefined, before, size })}`),
    rows: (activityId: number, page: number, size = 20) =>
      request<ActivityRowsResponse>(`/activity/${activityId}/rows${qs({ page, size })}`),
    // 그 항목이 건드린 행만 이전 본문으로 되돌린다(전파 없음). 되돌리기도 REVERT 항목으로 남는다.
    revert: (activityId: number) => request<ActivityRevertResult>(`/activity/${activityId}/revert`, { method: 'POST' }),
  },
  glossary: {
    list: (gameId: number, scope: 'GAME' | 'SHARED' | 'ALL') =>
      request<GlossaryEntry[]>(`/games/${gameId}/glossary${qs({ scope })}`),
    upsert: (gameId: number, body: GlossaryRequest) =>
      request<GlossarySaveResult>(`/games/${gameId}/glossary`, { method: 'POST', ...json(body) }),
    update: (id: number, body: GlossaryRequest) =>
      request<GlossarySaveResult>(`/glossary/${id}`, { method: 'PUT', ...json(body) }),
    remove: (id: number) => request<void>(`/glossary/${id}`, { method: 'DELETE' }),
    share: (id: number) => request<GlossaryEntry>(`/glossary/${id}/share`, { method: 'POST' }),
    count: (gameId: number, p: GlossaryRequest) =>
      request<{ count: number }>(`/games/${gameId}/glossary/apply/count${qs({ kind: p.kind, matchMode: p.matchMode, find: p.find, ko: p.ko ?? undefined, oldKo: p.oldKo ?? undefined })}`),
    // SRC+PARTIAL의 기존 표기 후보. ko는 예시 치환 결과에만 쓰인다.
    suggest: (gameId: number, p: { find: string; ko?: string }) =>
      request<GlossarySuggestResult>(`/games/${gameId}/glossary/suggest${qs({ find: p.find, ko: p.ko || undefined })}`),
    // 단어장에 아직 없는, 여러 문장에 되풀이되는 원문 구절 전부와 그 지금 번역. 게임 전체를 훑으므로 추천 창에서만 부른다.
    recommend: (gameId: number) => request<GlossaryRecommendation[]>(`/games/${gameId}/glossary/recommend`),
    // 같은 원문 구절이 다른 한국어로 옮겨진 곳. 게임 전체를 훑으므로 추천 창의 표기 갈림 탭에서만 부른다.
    variants: (gameId: number) => request<GlossaryVariant[]>(`/games/${gameId}/glossary/variants`),
    // 띄어쓰기만 다른 꼴을 여러 구절에서 한 번에 고친다(행만, 단어장엔 안 남김).
    unifySpacing: (gameId: number, items: GlossarySpacingItem[]) =>
      request<{ applied: number }>(`/games/${gameId}/glossary/unify-spacing`, { method: 'POST', ...json({ items }) }),
    unify: (gameId: number, body: GlossaryUnifyRequest) =>
      request<GlossarySaveResult>(`/games/${gameId}/glossary/unify`, { method: 'POST', ...json(body) }),
    // 구절의 DeepL 직역(중→한, 참고용). 서버에 DeepL 키가 없으면 빈 객체. 받은 원문만 키로 들어 있다.
    literal: (texts: string[]) => request<Record<string, string>>('/glossary/literal', { method: 'POST', ...json({ texts }) }),
    // 표기 갈림 구절의 분류. 처음 보는 구절만 서버가 GPT에 묻고 저장한다. 분류를 못 받은 구절은 키가 없다.
    categories: (gameId: number, items: { src: string; ko: string; example: string | null }[]) =>
      request<Record<string, GlossaryPhraseCategory>>(`/games/${gameId}/glossary/categories`, { method: 'POST', ...json({ items }) }),
    applyAll: (gameId: number) => request<{ applied: number }>(`/games/${gameId}/glossary/apply`, { method: 'POST' }),
  },
}
