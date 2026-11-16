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
  /** 시트 중 하나라도 최종 리뷰 버전(수정 한국어 열)으로 정규화했으면 true — 파일 목록의 "최종 리뷰" 배지. */
  finalReview?: boolean
  /** 정규화한 검수 행 수(건너뛴 행 빼고)와 그중 확정 행 수 — 파일 목록의 진행 막대. 옛 응답에는 없을 수 있다. */
  reviewRows?: number
  confirmedRows?: number
}

/** 게임 하나와 공유 자산 요약: 파일 수, 번역 메모리 문장 수, 마지막 업로드 시각, DeepL 지시문(한 줄에 하나). */
/** 게임마다 한 장인 팀 메모. content는 Tiptap 문서 JSON 문자열, 저장한 적 없으면 null·version 0. */
export interface GameMemo { content: string | null; version: number; updatedAt: string | null; updatedByName: string | null }

export interface Game {
  id: number; name: string; projects: number; memory: number; lastUploadedAt: string | null; createdAt: string; deeplInstructions?: string | null
  /** GPT 번역·다듬기 기본 지시문(서버 코드)과 이 게임이 덧붙인 글. */
  gptTranslateBase?: string; gptTranslateExtra?: string | null; gptPolishBase?: string; gptPolishExtra?: string | null
  /** 모든 파일의 검수 행 수와 그중 확정 행 수 — 게임 목록의 진행 막대. */
  reviewRows?: number; confirmedRows?: number
}

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
/** fixCol: 최종 리뷰 버전에서 이미 고친 한국어 열. 있으면 그 열을 Fix로 읽고 내보낼 때도 그 열에 쓴다(koHeader 없음). */
export interface ColumnPair { srcCol: string; enCol: string | null; koColMode: KoColMode; koCol: string | null; koHeader: string | null; fixCol?: string | null }
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
/** DeepL 번역 원문 언어. EN이면 영어가 있는 행만 영어로 보낸다(단어장은 중국어 기준이라 적용되지 않는다). */
export type SourceLang = 'ZH' | 'EN'
/** 정규화 뒤 빈 행 DeepL 번역 시작(POST /projects/{id}/translate). */
export interface TranslateStartRequest { sheet: string; sourceLang: SourceLang }
/** 백그라운드 작업 한 건(번역 등). */
export interface JobDto { id: number; projectId: number | null; kind: string; status: string; totalBatches: number; doneBatches: number; logs: string[] }
export interface QuickTranslateRequest { text: string; en?: string; basis?: Basis; instructions?: string }
/** source: MEMORY = 번역 메모리의 같은 원문을 그대로 씀(GPT 호출 없음). glossary·references는 이 번역에 실제로 적용한 것. */
/** source: DEEPL(새로 번역)·CACHE(전에 받은 DeepL 번역)·GLOSSARY(단어장 문장 통째)·MEMORY·LLM(GPT). problem: DeepL 태그 어긋남·중국어 남음. */
export interface QuickTranslateResult { ko: string; source: 'MEMORY' | 'LLM' | 'DEEPL' | 'CACHE' | 'GLOSSARY'; glossary: Record<string, string>; references: { cn: string; ko: string }[]; termMiss: string[]; placeholderOk: boolean; problem?: 'TAG' | 'HAN' | null }

export interface ReviewerRef { id: number; name: string; color: string }

export type RevisionAuthor = 'LLM' | 'HUMAN' | 'LLM_SHORTEN' | 'GLOSSARY' | 'EXISTING' | 'MEMORY' | 'LLM_PROOFREAD' | 'DEEPL' | 'IMPORTED'
/** reviewerName: HUMAN 판을 남긴 검토자. 익명이거나 사람이 아니면 없음. */
/** 행 하나의 변경 내역 한 판(2026-10-08, 우클릭 "변경 내역 보기"). 최신 판부터 온다. activityKind = 이 판을 만든 변경 내역 종류. */
export interface RowHistoryEntry {
  version: number; author: RevisionAuthor; text: string; createdAt: string
  reviewer: ReviewerRef | null; activityKind: ActivityKind | null
}
export interface RevisionDto { version: number; author: RevisionAuthor; text: string; createdAt: string; reviewerName?: string | null }
export interface SegmentRow {
  id: number; indexLabel: string; pairIndex?: number; srcText: string; enText: string | null
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
export interface SaveResult { row: SegmentRow; propagated: number; activityId: number | null }

export interface ExportPreview {
  fileName: string; outputFileName: string; sheetName: string; otherSheetCount: number
  /** 설정한 시트 이름, 설정한 순서(내보내기가 실제로 채우는 시트들). */
  sheets?: string[]
  filledRows: number; totalRows: number; flaggedRows: number
  rows: string[][]
  /** 기존 한국어 열을 읽고 새 열에 쓰는 시트가 있어 "기존 열에 덮어쓰기"를 고를 수 있는지. */
  canOverwrite?: boolean
  /** 최종 리뷰 시트가 있으면 최종 수정 표시를 켠 행 수, 없으면 null. */
  finalMarkedRows?: number | null
  /** "최종 수정분만"으로 받을 때의 파일 이름. */
  finalOutputFileName?: string | null
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
  | { type: 'marks'; by: number | null; segmentIds: number[]; review1: boolean | null; review2: boolean | null; review3?: boolean | null; needsCheck: boolean | null; confirmed: boolean | null; note: string | null; noteChanged: boolean; finalMark?: boolean | null }
  // 일괄 체크가 커서 행 목록 없이 온 신호: 그 시트 목록·요약을 다시 읽는다.
  | { type: 'marks-bulk'; by: number | null; sheet: string }

/** 검수 워크스페이스(설계 §1.2·§4). MINE은 헤더의 검토자 담당 행. */
/** 검색어를 찾을 칸. DEFAULT = 원문·Fix·비고(ID는 안 찾는다), EXISTING_KO = 기존 한국어에서만, SRC_EXACT = 원문이 글자 그대로 같은 행만(모아 보기), SRC_CONTAINS = 원문에 검색어가 든 행만(포함된 행 보기). */
export type SearchIn = 'DEFAULT' | 'EXISTING_KO' | 'SRC_EXACT' | 'SRC_CONTAINS'
export type RowFilter = 'ALL' | 'CHANGED' | 'NOT_REVIEW1' | 'NOT_REVIEW2' | 'NOT_REVIEW3' | 'NOT_CONFIRMED' | 'NEEDS_CHECK' | 'FINAL_MARK' | 'NOT_FINAL_MARK' | 'MINE' | 'UNASSIGNED' | 'TERM_MISS' | 'EMPTY_FIX' | 'JOSA' | 'DUP_CONFLICT'
export const ROW_FILTER_LABEL: Record<RowFilter, string> = {
  ALL: '전체', CHANGED: '수정됨', NOT_REVIEW1: 'A 안 함', NOT_REVIEW2: 'B 안 함', NOT_REVIEW3: 'C 안 함', NOT_CONFIRMED: '확정 안 함', NEEDS_CHECK: '확인 필요', FINAL_MARK: '최종 수정 표시', NOT_FINAL_MARK: '최종 수정 안 함', MINE: '내 담당', UNASSIGNED: '미배정', TERM_MISS: '용어 불일치', EMPTY_FIX: 'Fix 비어 있음', JOSA: '조사 확인', DUP_CONFLICT: '동일 원문 불일치',
}
/** 조사 제안(설계 2026-10-07). at = fix에서 from이 시작하는 위치(UTF-16, JS 문자열 인덱스와 같다). */
export type JosaKind = 'HANGUL' | 'NUMBER' | 'TAG'
export interface JosaHint { at: number; from: string; to: string; kind: JosaKind }
export interface JosaPreview { counts: Record<JosaKind, number>; examples: Record<JosaKind, string[]> }
/** 그리드 한 행. fix = 최신 리비전(없으면 null), changed = fix ≠ 기존 한국어(trim 비교).
 * dupCount: 이 게임 전체에서 같은 원문(src)을 쓰는 건너뛰지 않은 행 수. 원문이 한 곳뿐이면 0. */
export interface WorkspaceRow {
  id: number; rowNo: number; pairIndex: number; indexLabel: string; keyText: string | null
  src: string; en: string | null; existingKo: string | null; fix: string | null; changed: boolean
  termMiss: string[]; assignee: ReviewerRef | null
  // 조사 제안. 무시한 행은 빈 배열.
  josaHints: JosaHint[]
  // 검수 체크(1차·2차·확인 필요·확정, 서로 독립)와 비고. 확정만 행을 자동 변경(같은 원문 반영·단어장 일괄 적용·되돌리기)에서 뺀다 — 조사 고침은 예외로 확정 행도 고친다.
  review1: boolean; review2: boolean; review3: boolean; needsCheck: boolean; confirmed: boolean; note: string | null
  // 최종 리뷰 레인(설계 2026-10-17)이면 finalReview. finalMark는 사람이 켜는 "최종 수정" 표시 — 최종 리뷰 시트에서만 보이고 칠한다.
  finalReview: boolean; finalMark: boolean
  lastEditor: ReviewerRef | null; lastEditedAt: string | null; history: RevisionDto[]
  dupCount: number
  /** 이 행의 파일·시트. 게임 전체 리뷰 화면의 "파일 / 시트" 열에 쓴다. */
  projectId?: number; sheet?: string
}
/** 우클릭 "짧게 줄이기" 응답. ko·len은 줄인 대상(지금 Fix, 없으면 기존 한국어). 글자 수는 공백·자리표시자·태그를 뺀 것. 저장은 하지 않는다. */
export interface RowShortenResult { ko: string; len: number; target: number; candidates: { ko: string; len: number }[] }
export interface WorkspaceRowsResponse { rows: WorkspaceRow[]; page: number; size: number; total: number }
/** 검수 체크 칸 이름. */
export type MarkKey = 'review1' | 'review2' | 'review3' | 'needsCheck' | 'confirmed' | 'finalMark'
/** 행 체크·비고 변경. 준 칸만 바뀐다(1차·2차·확인 필요·확정은 서로 독립). */
export interface MarksPatch { review1?: boolean; review2?: boolean; review3?: boolean; needsCheck?: boolean; confirmed?: boolean; finalMark?: boolean; note?: string | null }
/** configId: 정규화 설정 id(게임 화면의 시트 고르기 값, 설정 없이 행만 남은 시트면 null). projectId·fileName: 그 시트의 파일. */
export interface SheetSummary {
  sheet: string; total: number; changed: number; review1: number; review2: number; review3: number; needsCheck: number; confirmed: number; josa: number; josaPending: number
  configId?: number | null; projectId?: number; fileName?: string
  // 그 시트의 레인(최종 리뷰면 true)과 최종 수정 표시 수
  finalReview?: boolean; finalMark?: number
}
/** sheets는 항상 전체(정규화 설정 순). 나머지는 sheet를 주면 그 시트 기준. reviewers[].done은 담당 행 중 2차 체크 수. */
export interface WorkspaceSummary {
  sheets: SheetSummary[]; total: number; changed: number; review1: number; review2: number; review3: number; needsCheck: number; confirmed: number
  unassigned: number; reviewers: ReviewerProgress[]; josa: number
  // 조사 검사 대기 행(누군가 연 파일·고친 행 중 아직 판정 전). 0보다 크면 화면이 요약을 주기적으로 다시 읽는다.
  josaPending: number
  finalMark?: number
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
 * renderings = 지금 번역(많아야 3개, 못 찾으면 빈 목록).
 */
export interface GlossaryRecommendation { find: string; sentences: number; rows: number; renderings: GlossaryRendering[] }

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
export interface TagSpacingRow { segmentId: number; rowNo: number; src: string; before: string; after: string; projectId?: number; sheet?: string }
export interface TagSpacingItem { segmentId: number; before: string; after: string }
/** 명령으로 고치기(2026-10-08). 규칙 = 행 조건(in: ko 번역 | src 원문) + 차례로 돌리는 찾아 바꾸기 단계. */
export interface CommandFilter { in: 'ko' | 'src'; contains: string }
export interface CommandStep { find: string; replace: string; regex: boolean }
/** SHORTEN(길이 줄이기)·TERM(원문 용어 바꾸기)은 GPT로 고쳐 다른 작업과 함께 쓰지 않는다. */
export type OpKind = 'REPLACE' | 'TAG_SPACING' | 'JOSA' | 'UNIFY_SAME_SOURCE' | 'SHORTEN' | 'TERM'
/** 작업 목록의 작업 하나. filter·steps는 REPLACE만, src(원문 용어)·ko(바꿀 한국어)는 TERM만. */
export interface OpSpec { kind: OpKind; filter?: CommandFilter | null; steps?: CommandStep[]; src?: string; ko?: string }
export interface CommandWarning { kind: 'JOSA' | 'TAG' | 'LENGTH' | 'INSERT'; text: string }
/** FIX면 after·by·warnings, CHECK면 mismatch(after는 null). target은 길이 줄이기·길이 검사의 목표 글자 수. */
export interface CommandRow {
  segmentId: number; rowNo: number; src: string; before: string; after: string | null
  by: OpKind[]; warnings: CommandWarning[]; mismatch: { src: string[]; ko: string[] } | null
  projectId?: number; sheet?: string; target?: number | null
}
export interface CommandTrace { tool: string; args: string; result: string }
export interface CommandPlan { summary: string | null; kind: 'FIX' | 'CHECK'; ops: OpSpec[] | null; trail: CommandTrace[]; rows: CommandRow[] }
export interface CommandPreset { id: number; name: string; ops: OpSpec[] }

/** 변경 내역(설계 2026-09-30). */
export type ActivityKind = 'EDIT' | 'RETRANSLATE' | 'FIX_TERMS' | 'SHORTEN' | 'GLOSSARY_APPLY' | 'GLOSSARY_EDIT' | 'REVERT' | 'JOSA_FIX' | 'TAG_SPACING' | 'COMMAND_FIX' | 'POLISH' | 'RULE_APPLY'
export const ACTIVITY_KIND_LABEL: Record<ActivityKind, string> = {
  EDIT: '번역 수정', RETRANSLATE: '다시 번역', FIX_TERMS: '용어 치환', SHORTEN: '줄이기', GLOSSARY_APPLY: '단어장 일괄 적용', GLOSSARY_EDIT: '단어장 편집', REVERT: '되돌림', JOSA_FIX: '조사 고침', TAG_SPACING: '태그 띄어쓰기', COMMAND_FIX: '명령으로 고치기', POLISH: 'GPT로 다듬기', RULE_APPLY: '교정 규칙 적용',
}
export type GlossaryAction = 'ADD' | 'UPDATE' | 'DELETE' | 'SHARE'
export const GLOSSARY_ACTION_LABEL: Record<GlossaryAction, string> = { ADD: '추가', UPDATE: '수정', DELETE: '삭제', SHARE: '공유' }
/** projectId: 그 행이 든 파일 — 같은 원문 전파는 게임의 다른 파일 행도 바꾸므로 지금 프로젝트와 다를 수 있다(점프는 같은 파일만).
 * before: 바로 앞 판(첫 판이면 null). ordinal: 그 시트 전체 보기에서의 0-기반 위치 — 점프에 쓴다. */
export interface ActivityRowItem { segmentId: number; projectId: number; sheet: string; rowNo: number; ordinal: number; before: string | null; after: string }
/** 여러 행 다시 번역의 엔진. 서버 설정과 무관하게 요청이 고른다. */
export type TranslateEngine = 'DEEPL' | 'GPT'
/** 여러 행 다시 번역 결과. skipped.reason: 확정 | 제외 | 번역 결과 없음. activityIds: 다음 묶음에 그대로 넘길 프로젝트 id → 변경 내역 id. */
export interface BatchTranslateResult { rows: WorkspaceRow[]; skipped: { id: number; reason: string }[]; activityIds: Record<string, number> }
/** GPT로 다듬기 제안 하나. before: 지금 Fix, after: 고친 안. */
export interface PolishProposal { segmentId: number; before: string; after: string; reason: string | null }
/** 다듬기 제안(저장 안 함). kept: 고칠 데 없다고 본 행 수. skipped.reason: 확정 | 제외 | Fix 없음 | 자리표시자 어긋남 | 다듬기 결과 없음. */
export interface PolishResult { items: PolishProposal[]; kept: number; skipped: { id: number; reason: string }[] }
/** 일괄 넣기 한 행. base: 제안을 받을 때의 Fix(before) — 그 사이 바뀌었으면 서버가 건너뛴다. */
export interface SaveBatchItem { segmentId: number; text: string; base: string | null }
/** propagated: 같은 원문이라 따라 바뀐 행 수. activityIds: 이번에 만든 POLISH 변경 내역(파일마다 하나) — Ctrl+Z에 쓴다. */
export interface SaveBatchResult { saved: number; skipped: number; propagated: number; activityIds: number[] }
export interface ActivityFeedItem {
  id: number; kind: ActivityKind; reviewer: ReviewerRef | null; createdAt: string; rowCount: number; sheet: string | null
  /** EDIT·RETRANSLATE의 대상 행. 같은 값을 다시 저장해 대상 행엔 판이 없으면 null. */
  primary: ActivityRowItem | null
  detail: { segmentId?: number; sheet?: string; action?: GlossaryAction; kind?: GlossaryKind; find?: string; ko?: string | null; oldKo?: string | null; revertOf?: number; engine?: TranslateEngine }
  /** 항목의 파일. 단어장 편집처럼 게임 단위면 null. */
  projectId?: number | null
}
/** 되돌리기 결과. skipped: 그 사이 바뀐 행·확정 행·첫 판 행처럼 손대지 않은 행 수. */
export interface ActivityRevertResult { reverted: number; skipped: number }
export interface ActivityFeedResponse { items: ActivityFeedItem[]; nextBefore?: number }
export interface ActivityRowsResponse { rows: ActivityRowItem[]; page: number; size: number; total: number }

/** 단어장 요청(개발사 양식) — 서버 RequestDto/ItemDto/RequestSummary. */
export type TermCategory = 'CHARACTER' | 'ITEM' | 'SKILL' | 'PLACE' | 'MONSTER' | 'CURRENCY' | 'OTHER'
export type TermItemStatus = 'READY' | 'PENDING' | 'FAILED' | 'APPROVED'
export interface TermRequestItem {
  id: number; seq: number; category: TermCategory; src: string
  renderings: GlossaryRendering[]; source: 'CORPUS' | 'GPT'; gptKo: string | null; gptReason: string | null
  status: TermItemStatus; finalKo: string | null; existingKo: string | null; existingNote: string | null
  /** GPT로 표기 확인을 마쳤는지와 그 까닭(2026-11-02). */
  gptChecked: boolean; gptFindReason: string | null
}
export interface TermRequestDetail { id: number; gameId: number; fileName: string; createdAt: string; items: TermRequestItem[] }
export interface TermRequestSummary { id: number; fileName: string; createdAt: string; total: number; approved: number; pending: number }
export interface TermApproveItem { itemId: number; ko: string; replace: string[] }
export interface TermApproveResult { approved: number; applied: number; request: TermRequestDetail }

/** 교정 규칙(설계 2026-11-13) — 문체(서술식→개조식)만. ratio = 개조식으로 바꾼 비율. verdict: RULE|KEEP|DECIDE|INSUFFICIENT. */
export interface CorrectionRule {
  id: number; type: 'WORD' | 'STYLE' | 'PERIOD'; of: string; nf: string; textKind: string | null; anchor: string | null; anchors: string[]
  fixed: number; left: number; candidates: number; ratio: number; sameAsHuman: number; flipped: boolean
  status: 'SUGGESTED' | 'APPROVED' | 'IGNORED'; verdict: string | null; converted: number; kept: number; appliedCount: number; lastEdit: string | null
}
export interface RuleList { learnedAt: string | null; unjudgedRows: number; estimateUsd: number; rules: CorrectionRule[] }
export interface RulePreviewRow {
  segmentId: number; projectId: number; fileName: string | null; sheet: string | null; rowNo: number; src: string
  before: string; after: string; complete: boolean; checked: boolean
}
export interface RulePreview {
  rows: RulePreviewRow[]; excluded: RulePreviewRow[]
}
export interface RuleApplyRequest { checked: { segmentId: number; before: string; after: string }[]; unchecked: number[] }
export interface RuleApplyResult { applied: number; skippedChanged: number }
