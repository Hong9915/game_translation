export interface Project {
  id: number
  fileName: string
  gameId: number
  gameName: string
  sheetCount: number
  rowCount: number
  uploadedAt: string
  currentStep: number
  /** 검수 전용: 정규화 → 검수 → 내보내기만 거친다. 옛 응답에는 없을 수 있다. */
  reviewOnly?: boolean
}

/** 게임 하나와 공유 자산 요약: 파일 수, 공용 용어 수, 번역 메모리 문장 수, 마지막 업로드 시각. */
export interface Game { id: number; name: string; projects: number; terms: number; memory: number; lastUploadedAt: string | null; createdAt: string }

/** LLM 사용량 합계. priced가 false면 가격 정보가 없어 비용 대신 토큰 수만 쓴다. */
export interface UsageSummary { calls: number; promptTokens: number; completionTokens: number; reasoningTokens: number; costUsd: number; priced: boolean }

export interface HeaderCell { letter: string; text: string }
/** 헤더 접미사(_CN/_EN/_KR 등)로 묶은 열 쌍 추측. ko가 있으면 기존 열 모드로 미리 채운다. */
export interface PairGuess { src: string; en: string | null; ko: string | null }
export interface Guess { src: string | null; en: string | null; key: string | null; pairs: PairGuess[] }
/** preview: 헤더 아래 데이터 행 몇 줄(열 순서 그대로). 정규화 화면의 미리보기. */
export interface SheetOption { name: string; rowCount: number; headerRow: number; headers: HeaderCell[]; guess: Guess; preview: string[][] }
export interface SheetsResponse { sheets: SheetOption[] }

export type KoColMode = 'NEW' | 'EXISTING' | 'EXISTING_TO_NEW'
/** 원문 열 하나 → 한국어 열 하나. 한 시트에 이름·설명처럼 원문 열이 여러 개면 쌍도 여러 개. */
export interface ColumnPair { srcCol: string; enCol: string | null; koColMode: KoColMode; koCol: string | null; koHeader: string | null }
export interface NormalizeRequest {
  sheetName: string; headerRow: number; keyCol: string | null
  pairs: ColumnPair[]
  /** EXISTING 모드에서 기존 한국어가 있는 행도 다시 번역할지. false면 기존 문장을 0차(기존 번역)로 넣고 번역 대상에서 뺀다. */
  retranslateExisting: boolean
  /** 기존 한국어를 살린 채 번역 단계에서 GPT가 틀린 행만 골라 고친다. retranslateExisting과 함께 켤 수 없다. */
  proofreadExisting?: boolean
}
/** GET .../normalize가 돌려주는 한 시트의 저장된 설정. 모양은 NormalizeRequest와 같다. */
export type NormalizeConfigDto = NormalizeRequest
/** kept: 기존 한국어를 살린 행. intentional: 영어·한국어가 모두 N/A라 일부러 비워 둔 것으로 보고 건너뛴 행(skipped에 포함). */
export interface NormalizeStats { totalRows: number; normalized: number; skipped: number; withEn: number; kept: number; intentional: number }
export interface SegmentPreview { id: number; indexLabel: string; pairIndex: number; keyText: string | null; srcText: string; enText: string | null; existingKoText: string | null }
export interface Paged { page?: number; size?: number; total?: number }
/** 설정한 시트 하나: 그 시트의 설정과 그 시트만의 통계. */
export interface SheetConfig { config: NormalizeConfigDto; stats: NormalizeStats }
/** configs는 설정한 순서. stats는 프로젝트 전체, configs[i].stats는 그 시트만. sheet=를 주면 preview/total이 그 시트만. */
export interface NormalizeResponse extends Paged { configs: SheetConfig[]; stats: NormalizeStats; preview: SegmentPreview[] }

// 빠른 번역 화면(GamePage의 QuickTranslate)이 기준 열을 고르는 데 쓴다.
export type Basis = 'EN_FIRST' | 'CN_ONLY'
export interface QuickTranslateRequest { text: string; en?: string; basis?: Basis; instructions?: string }
/** source: MEMORY = 번역 메모리의 같은 원문을 그대로 씀(GPT 호출 없음). glossary·references는 이 번역에 실제로 적용한 것. */
export interface QuickTranslateResult { ko: string; source: 'MEMORY' | 'LLM'; glossary: Record<string, string>; references: { cn: string; ko: string }[]; termMiss: string[]; placeholderOk: boolean }

export interface ReviewerRef { id: number; name: string; color: string }

export type RevisionAuthor = 'LLM' | 'HUMAN' | 'LLM_SHORTEN' | 'GLOSSARY' | 'EXISTING' | 'MEMORY' | 'LLM_PROOFREAD'
/** reviewerName: HUMAN 판을 남긴 검토자. 익명이거나 사람이 아니면 없음. */
/** 행 하나의 변경 내역 한 판(2026-10-08, 우클릭 "변경 내역 보기"). 최신 판부터 온다. activityKind = 이 판을 만든 변경 내역 종류. */
export interface RowHistoryEntry {
  version: number; author: RevisionAuthor; text: string; createdAt: string
  reviewer: ReviewerRef | null; activityKind: ActivityKind | null
}
export interface RevisionDto { version: number; author: RevisionAuthor; text: string; createdAt: string; reviewerName?: string | null }
export interface SegmentRow {
  id: number; indexLabel: string; pairIndex?: number; srcText: string; enText: string | null; isTerm: boolean
  currentText: string | null; lenKo: number; lenSrc: number; flagged: boolean
  /** 확정 용어의 한국어가 번역에 빠진 경우 "원문→한국어" 목록. 비어 있으면 통과. */
  termMiss?: string[]
  /** 교정 모드에서 GPT가 기존 한국어를 고친 사유. 고치지 않았으면 없음. */
  proofNote?: string | null
  /** 기존 한국어가 있는데 아직 GPT 교정에서 유지/수정 판정을 받지 않은 행. */
  proofPending?: boolean
  history: RevisionDto[]
  sheetName?: string; rowNo?: number
  /** 담당자. 없으면 미배정. 담당은 표시·필터용이라 남의 행도 고칠 수 있다. */
  assignee?: ReviewerRef | null
  done?: boolean
  lastEditor?: ReviewerRef | null; lastEditedAt?: string | null
}
/** 셀 저장 결과. propagated: 원문이 같아서 같이 바뀐 다른 행 수. */
export interface SaveResult { row: SegmentRow; propagated: number }

export interface ExportPreview {
  fileName: string; outputFileName: string; sheetName: string; otherSheetCount: number
  /** 설정한 시트 이름, 설정한 순서(내보내기가 실제로 채우는 시트들). */
  sheets?: string[]
  filledRows: number; totalRows: number; flaggedRows: number
  rows: string[][]
}

export interface ReviewerProgress { reviewer: ReviewerRef; assigned: number; done: number }
/** segmentIds(그 칸이 있는 행 전체) 또는 sheet+fromRow+toRow. assigneeId가 null이면 배정 해제. */
export interface AssignRequest { sheet?: string; segmentIds?: number[]; fromRow?: number; toRow?: number; assigneeId: number | null }

/** 지금 같은 프로젝트를 보고 있는 한 사람. editingSegmentId는 지금 편집 중인 칸(없으면 null). */
export interface PresenceEntry { id: number; name: string; color: string; sheet: string | null; editingSegmentId: number | null }
/** 프로젝트 SSE 스트림의 `review` 이벤트 payload. presence만 by가 없다(누구 하나의 행동이 아니라 전체 스냅샷). */
export type ReviewEvent =
  | { type: 'segment-updated'; by: number | null; row: SegmentRow }
  | { type: 'assigned'; by: number | null; segmentIds: number[]; assignee: ReviewerRef | null }
  | { type: 'presence'; reviewers: PresenceEntry[] }
  | { type: 'refresh'; by: number | null }
  | { type: 'glossary'; by: number | null }
  // 검수 체크·비고. null인 체크는 안 바뀐 것, noteChanged면 note(null이면 지움)를 반영한다.
  | { type: 'marks'; by: number | null; segmentIds: number[]; review1: boolean | null; review2: boolean | null; needsCheck: boolean | null; confirmed: boolean | null; note: string | null; noteChanged: boolean }
  // 일괄 체크가 커서 행 목록 없이 온 신호: 그 시트 목록·요약을 다시 읽는다.
  | { type: 'marks-bulk'; by: number | null; sheet: string }

/** 검수 워크스페이스(설계 §1.2·§4). MINE은 헤더의 검토자 담당 행. */
export type RowFilter = 'ALL' | 'CHANGED' | 'NOT_REVIEW1' | 'NOT_REVIEW2' | 'NOT_CONFIRMED' | 'NEEDS_CHECK' | 'MINE' | 'UNASSIGNED' | 'TERM_MISS' | 'EMPTY_FIX' | 'JOSA' | 'DUP_CONFLICT'
export const ROW_FILTER_LABEL: Record<RowFilter, string> = {
  ALL: '전체', CHANGED: '수정됨', NOT_REVIEW1: 'A 안 함', NOT_REVIEW2: 'B 안 함', NOT_CONFIRMED: '확정 안 함', NEEDS_CHECK: '확인 필요', MINE: '내 담당', UNASSIGNED: '미배정', TERM_MISS: '용어 불일치', EMPTY_FIX: 'Fix 비어 있음', JOSA: '조사 확인', DUP_CONFLICT: '동일 원문 불일치',
}
/** 조사 제안(설계 2026-10-07). at = fix에서 from이 시작하는 위치(UTF-16, JS 문자열 인덱스와 같다). */
export type JosaKind = 'HANGUL' | 'NUMBER' | 'TAG'
export interface JosaHint { at: number; from: string; to: string; kind: JosaKind }
export interface JosaPreview { counts: Record<JosaKind, number>; examples: Record<JosaKind, string[]> }
/** 그리드 한 행. fix = 최신 리비전(없으면 null), changed = fix ≠ 기존 한국어(trim 비교).
 * dupCount: 이 게임 전체에서 같은 원문(src)을 쓰는 건너뛰지 않은·용어 아닌 행 수. 원문이 한 곳뿐이면 0. */
export interface WorkspaceRow {
  id: number; rowNo: number; pairIndex: number; indexLabel: string; keyText: string | null
  src: string; en: string | null; existingKo: string | null; fix: string | null; changed: boolean
  termMiss: string[]; assignee: ReviewerRef | null
  // 조사 제안. 무시한 행은 빈 배열.
  josaHints: JosaHint[]
  // 검수 체크(1차·2차·확인 필요·확정, 서로 독립)와 비고. 확정만 행을 자동 변경(같은 원문 반영·단어장 일괄 적용·되돌리기)에서 뺀다 — 조사 고침은 예외로 확정 행도 고친다.
  review1: boolean; review2: boolean; needsCheck: boolean; confirmed: boolean; note: string | null
  lastEditor: ReviewerRef | null; lastEditedAt: string | null; history: RevisionDto[]
  dupCount: number
}
/** 우클릭 "짧게 줄이기" 응답. ko·len은 줄인 대상(지금 Fix, 없으면 기존 한국어). 글자 수는 공백·자리표시자·태그를 뺀 것. 저장은 하지 않는다. */
export interface RowShortenResult { ko: string; len: number; target: number; candidates: { ko: string; len: number }[] }
export interface WorkspaceRowsResponse { rows: WorkspaceRow[]; page: number; size: number; total: number }
/** 검수 체크 칸 이름. */
export type MarkKey = 'review1' | 'review2' | 'needsCheck' | 'confirmed'
/** 행 체크·비고 변경. 준 칸만 바뀐다(1차·2차·확인 필요·확정은 서로 독립). */
export interface MarksPatch { review1?: boolean; review2?: boolean; needsCheck?: boolean; confirmed?: boolean; note?: string | null }
export interface SheetSummary { sheet: string; total: number; changed: number; review1: number; review2: number; needsCheck: number; confirmed: number; josa: number; josaPending: number }
/** sheets는 항상 전체(정규화 설정 순). 나머지는 sheet를 주면 그 시트 기준. reviewers[].done은 담당 행 중 2차 체크 수. */
export interface WorkspaceSummary {
  sheets: SheetSummary[]; total: number; changed: number; review1: number; review2: number; needsCheck: number; confirmed: number
  unassigned: number; reviewers: ReviewerProgress[]; josa: number
  // 조사 검사 대기 행(누군가 연 파일·고친 행 중 아직 판정 전). 0보다 크면 화면이 요약을 주기적으로 다시 읽는다.
  josaPending: number
}

// 단어장
export type GlossaryKind = 'SRC' | 'KO'
export type GlossaryMatchMode = 'WHOLE' | 'PARTIAL'
/** oldKo: SRC+PARTIAL 전용 "지금 번역에서 find를 옮긴 표기". 있으면 원문에 find가 든 행에서만 oldKo → ko로 치환된다. */
export interface GlossaryEntry { id: number; gameId?: number; kind: GlossaryKind; matchMode: GlossaryMatchMode; find: string; ko: string | null; note: string | null; oldKo: string | null; shared: boolean; updatedAt: string }
export interface GlossaryRequest { kind: GlossaryKind; matchMode: GlossaryMatchMode; find: string; ko?: string | null; note?: string | null; oldKo?: string | null }
export interface GlossarySaveResult { entry: GlossaryEntry; applied: number }
/** 기존 표기 후보 하나. example.after는 ko를 함께 보냈을 때만 있다. */
export interface GlossarySuggestCandidate { oldKo: string; rows: number; example: { src: string; before: string; after: string | null } }
export interface GlossarySuggestResult { rows: number; candidates: GlossarySuggestCandidate[] }
/** 추천 구절의 지금 번역 하나: 그 표기가 든 행 수와 예문 한 쌍. */
export interface GlossaryRendering { ko: string; rows: number; exampleSrc: string; exampleKo: string }
/**
 * 단어장 추천 한 줄. sentences = 그 구절이 든 서로 다른 문장 수(숫자만 다른 행은 한 문장, 순위 기준), rows = 그 구절이 든 행 수,
 * renderings = 지금 번역(많아야 3개, 못 찾으면 빈 목록), termKo = 옛 파이프라인 용어집에 같은 원문이 있으면 그 번역.
 */
export interface GlossaryRecommendation { find: string; sentences: number; rows: number; renderings: GlossaryRendering[]; termKo: string | null }

/** 표기 갈림(설계 2026-10-07). ALT = 다른 표기 있음, SPACING = 띄어쓰기만 다름, MISSING = 뚜렷한 주 표기가 몇 행에만 없음. */
export type GlossaryVariantKind = 'ALT' | 'SPACING' | 'MISSING'
/** 표기 갈림 구절의 분류(GPT가 게임마다 한 번 매겨 저장). */
export type GlossaryPhraseCategory =
  | 'CHARACTER' | 'ITEM' | 'SKILL' | 'PLACE' | 'NAME_OTHER' | 'STAT' | 'NOUN' | 'VERB' | 'COLOR' | 'MODIFIER' | 'FUNCTION' | 'PHRASE'
export interface GlossaryVariantExample { src: string; ko: string }
/** spacing = 주 표기와 띄어쓰기만 다른 꼴. rows = 그 표기가 든 행 수. */
export interface GlossaryVariantRendering { ko: string; rows: number; spacing: boolean; examples: GlossaryVariantExample[] }
export interface GlossaryVariant {
  find: string; kind: GlossaryVariantKind; rows: number; main: GlossaryVariantRendering; alts: GlossaryVariantRendering[]
  missingRows: number; missing: GlossaryVariantExample[]
  /** SPACING 카드에서 Kiwi 띄어쓰기 교정이 고른 꼴(쓰인 꼴 가운데 하나일 때만). 없으면 null. */
  standard: string | null
}
/** replace = 행에서 ko로 바꿀 표기, keep = 그대로 둘 표기(있으면 단어장엔 메모로 남는다). */
export interface GlossaryUnifyRequest { find: string; ko: string; replace: string[]; keep: string[] }
/** 띄어쓰기 한꺼번에 통일의 한 구절. replace는 ko와 공백만 달라야 한다. 단어장엔 남지 않는다. */
export interface GlossarySpacingItem { find: string; ko: string; replace: string[] }

/** 태그 경계 띄어쓰기 후보 한 행(2026-10-08). after = before에 태그 경계 공백만 더한 꼴. */
export interface TagSpacingRow { segmentId: number; rowNo: number; src: string; before: string; after: string }
export interface TagSpacingItem { segmentId: number; before: string; after: string }
/** 명령으로 고치기(2026-10-08). 규칙 = 행 조건(in: ko 번역 | src 원문) + 차례로 돌리는 찾아 바꾸기 단계. */
export interface CommandFilter { in: 'ko' | 'src'; contains: string }
export interface CommandStep { find: string; replace: string; regex: boolean }
export type OpKind = 'REPLACE' | 'TAG_SPACING' | 'JOSA' | 'UNIFY_SAME_SOURCE'
/** 작업 목록의 작업 하나. filter·steps는 REPLACE만. */
export interface OpSpec { kind: OpKind; filter?: CommandFilter | null; steps?: CommandStep[] }
export interface CommandWarning { kind: 'JOSA' | 'TAG'; text: string }
/** FIX면 after·by·warnings, CHECK면 mismatch(after는 null). */
export interface CommandRow {
  segmentId: number; rowNo: number; src: string; before: string; after: string | null
  by: OpKind[]; warnings: CommandWarning[]; mismatch: { src: string[]; ko: string[] } | null
}
export interface CommandTrace { tool: string; args: string; result: string }
export interface CommandPlan { summary: string | null; kind: 'FIX' | 'CHECK'; ops: OpSpec[] | null; trail: CommandTrace[]; rows: CommandRow[] }
export interface CommandPreset { id: number; name: string; ops: OpSpec[] }

/** 변경 내역(설계 2026-09-30). */
export type ActivityKind = 'EDIT' | 'RETRANSLATE' | 'FIX_TERMS' | 'SHORTEN' | 'GLOSSARY_APPLY' | 'GLOSSARY_EDIT' | 'REVERT' | 'JOSA_FIX' | 'TAG_SPACING' | 'COMMAND_FIX'
export const ACTIVITY_KIND_LABEL: Record<ActivityKind, string> = {
  EDIT: '번역 수정', RETRANSLATE: '다시 번역', FIX_TERMS: '용어 치환', SHORTEN: '줄이기', GLOSSARY_APPLY: '단어장 일괄 적용', GLOSSARY_EDIT: '단어장 편집', REVERT: '되돌림', JOSA_FIX: '조사 고침', TAG_SPACING: '태그 띄어쓰기', COMMAND_FIX: '명령으로 고치기',
}
export type GlossaryAction = 'ADD' | 'UPDATE' | 'DELETE' | 'SHARE'
export const GLOSSARY_ACTION_LABEL: Record<GlossaryAction, string> = { ADD: '추가', UPDATE: '수정', DELETE: '삭제', SHARE: '공유' }
/** projectId: 그 행이 든 파일 — 같은 원문 전파는 게임의 다른 파일 행도 바꾸므로 지금 프로젝트와 다를 수 있다(점프는 같은 파일만).
 * before: 바로 앞 판(첫 판이면 null). ordinal: 그 시트 전체 보기에서의 0-기반 위치 — 점프에 쓴다. */
export interface ActivityRowItem { segmentId: number; projectId: number; sheet: string; rowNo: number; ordinal: number; before: string | null; after: string }
export interface ActivityFeedItem {
  id: number; kind: ActivityKind; reviewer: ReviewerRef | null; createdAt: string; rowCount: number; sheet: string | null
  /** EDIT·RETRANSLATE의 대상 행. 같은 값을 다시 저장해 대상 행엔 판이 없으면 null. */
  primary: ActivityRowItem | null
  detail: { segmentId?: number; sheet?: string; action?: GlossaryAction; kind?: GlossaryKind; find?: string; ko?: string | null; oldKo?: string | null; revertOf?: number }
}
/** 되돌리기 결과. skipped: 그 사이 바뀐 행·확정 행·첫 판 행처럼 손대지 않은 행 수. */
export interface ActivityRevertResult { reverted: number; skipped: number }
export interface ActivityFeedResponse { items: ActivityFeedItem[]; nextBefore?: number }
export interface ActivityRowsResponse { rows: ActivityRowItem[]; page: number; size: number; total: number }
