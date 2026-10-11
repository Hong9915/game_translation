import type {
  AssignRequest, ExportPreview, Game, NormalizeRequest, NormalizeResponse, PresenceEntry, Project,
  MarkKey, MarksPatch, QuickTranslateRequest, QuickTranslateResult, ReviewerRef, RowFilter, RowShortenResult, WorkspaceRow, WorkspaceRowsResponse, WorkspaceSummary,
  SaveResult, SegmentRow, SheetsResponse, UsageSummary,
  GlossaryEntry, GlossaryPhraseCategory, GlossaryRecommendation, GlossaryRequest, GlossarySaveResult, GlossarySuggestResult, GlossaryVariant, GlossaryUnifyRequest, GlossarySpacingItem, TagSpacingItem, TagSpacingRow, CommandPlan, OpSpec, CommandPreset, RowHistoryEntry,
  ActivityFeedResponse, ActivityRevertResult, ActivityRowsResponse, JosaKind, JosaPreview,
} from './types'
import { loadReviewer } from '../lib/reviewer'

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

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })
const qs = (params: Record<string, string | number | boolean | undefined>): string => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined) p.set(k, String(v))
  const s = p.toString()
  return s ? `?${s}` : ''
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
  sheets: (id: number, headerRow: number) => request<SheetsResponse>(`/projects/${id}/sheets${qs({ headerRow })}`),
  normalize: (id: number, body: NormalizeRequest) => request<NormalizeResponse>(`/projects/${id}/normalize`, { method: 'PUT', ...json(body) }),
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
  translateRow: (segmentId: number, instructions?: string) =>
    request<WorkspaceRow>(`/segments/${segmentId}/translate`, { method: 'POST', ...json({ instructions: instructions || undefined }) }),
  // 행의 지금 한국어를 GPT로 줄인 후보만 받는다(저장 안 함). maxChars가 없으면 서버가 원문 글자 수 × ratio로 정한다.
  shortenRow: (segmentId: number, opts: { maxChars?: number; ratio?: number } = {}) =>
    request<RowShortenResult>(`/segments/${segmentId}/shorten`, { method: 'POST', ...json(opts) }),
  // 검수 체크·비고. 준 칸만 바뀌고 그리드 한 행이 돌아온다.
  setMarks: (segmentId: number, patch: MarksPatch) =>
    request<WorkspaceRow>(`/segments/${segmentId}/marks`, { method: 'PUT', ...json(patch) }),
  setDone: (segmentId: number, done: boolean, ratio?: number) =>
    request<SegmentRow>(`/segments/${segmentId}/done${qs({ ratio })}`, { method: 'PUT', ...json({ done }) }),
  // 검수 워크스페이스: 시트 한 페이지를 DB에서 잘라 받는다(옛 segments API처럼 프로젝트 전 행을 올리지 않는다).
  workspace: {
    rows: (id: number, p: { sheet: string; page?: number; size?: number; filter?: RowFilter; q?: string }) =>
      request<WorkspaceRowsResponse>(`/review/${id}/rows${qs({ sheet: p.sheet, page: p.page ?? 0, size: p.size ?? 500, filter: p.filter === 'ALL' ? undefined : p.filter, q: p.q || undefined })}`),
    summary: (id: number, sheet?: string) => request<WorkspaceSummary>(`/review/${id}/rows/summary${qs({ sheet })}`),
    // 머리글 일괄 체크: ids가 있으면 그 행만(쪽 보기의 화면 행), 없으면 목록과 같은 조건(시트·필터·검색)의 행 전부를 체크·해제한다.
    bulkMarks: (id: number, body: { sheet: string; filter?: RowFilter; q?: string; ids?: number[] } & Partial<Record<MarkKey, boolean>>) =>
      request<{ updated: number }>(`/review/${id}/marks`, { method: 'POST', ...json({ ...body, filter: body.filter === 'ALL' ? undefined : body.filter, q: body.q || undefined }) }),
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
    commandPlan: (id: number, body: { sheet: string; command?: string; ops?: OpSpec[]; check?: 'TAG_MISMATCH' }) =>
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
  exportPreview: (id: number, ratio: number, sheet?: string) => request<ExportPreview>(`/projects/${id}/export/preview${qs({ ratio, sheet })}`),
  exportXlsx: async (id: number, opts: { addFlagColumn: boolean; addHistorySheet: boolean; addGlossarySheet: boolean; ratio: number }): Promise<Blob> => {
    const res = await fetch(`${API_BASE}/projects/${id}/export`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(opts),
    })
    if (!res.ok) throw new ApiError(res.status, '내보내기에 실패했습니다.')
    return res.blob()
  },
  activity: {
    // 최신순 커서 페이지. before는 그보다 작은 id만.
    feed: (projectId: number, before?: number, size?: number) =>
      request<ActivityFeedResponse>(`/projects/${projectId}/activity${qs({ before, size })}`),
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
