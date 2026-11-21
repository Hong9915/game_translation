import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js'
import { z } from 'zod'
import { BackendError } from './backend.js'
import { memoToText } from './memoText.js'
import type { BackendClient, BackendRow, SheetInfo } from './backend.js'

export interface ToolContext { backend: BackendClient; reviewerId: number }

const MAX_SIZE = 200
const DEFAULT_SIZE = 50

const findRowsShape = {
  gameId: z.number().int().optional().describe('게임 id (/games/{gameId}/review)'),
  projectId: z.number().int().optional().describe('파일(프로젝트) id (/review/{projectId})'),
  sheet: z.string().optional().describe('시트 이름'),
  q: z.string().optional().describe('찾을 말'),
  qIn: z.enum(['DEFAULT', 'EXISTING_KO', 'SRC_EXACT', 'SRC_CONTAINS']).optional().describe('찾을 칸'),
  filter: z
    .enum(['CHANGED', 'NOT_CONFIRMED', 'NEEDS_CHECK', 'TERM_MISS', 'DUP_CONFLICT', 'EMPTY_FIX'])
    .optional()
    .describe('행 거르기'),
  page: z.number().int().min(0).optional().describe('쪽 번호 (0부터)'),
  size: z.number().int().min(1).optional().describe('한 쪽 행 수 (기본 50, 최대 200)'),
}
const saveRowsShape = {
  items: z
    .array(
      z.object({
        rowId: z.number().int(),
        text: z.string().describe('새 수정 문장'),
        base: z.string().nullable().describe('find_rows에서 본 fix. 비어 있었으면 null'),
      }),
    )
    .min(1)
    .max(500),
}
const setNoteShape = {
  items: z
    .array(
      z.object({
        rowId: z.number().int(),
        note: z.string().optional(),
        needsCheck: z.boolean().optional(),
        mode: z.enum(['append', 'replace']).optional(),
      }),
    )
    .min(1)
    .max(500),
}
const proposeRowsShape = {
  title: z.string().max(200).optional(),
  items: z
    .array(
      z.object({
        rowId: z.number().int(),
        text: z.string().min(1).describe('새 수정 문장'),
        base: z
          .string()
          .nullable()
          .optional()
          .describe('find_rows에서 본 fix. 비어 있었으면 null. 생략하면 서버가 지금 fix로 채운다'),
        reason: z.string().optional().describe('왜 바꾸는지 한 줄'),
      }),
    )
    .min(1)
    .max(500),
}
const proposeReplaceShape = {
  gameId: z.number().int().optional().describe('게임 id (/games/{gameId}/review)'),
  projectId: z.number().int().optional().describe('파일(프로젝트) id (/review/{projectId})'),
  sheet: z.string().optional().describe('시트 이름으로 좁히기'),
  find: z.string().min(1).max(200).describe('지금 한국어에서 찾을 말(조사 없는 낱말)'),
  replace: z.string().max(200).describe('바꿀 말(조사 없는 낱말). 빈 글이면 지우기'),
  src: z.string().min(1).max(100).optional().describe('용어 바꾸기일 때만: 그 한국어가 옮긴 원문(간체) 용어'),
  reason: z.string().max(200).optional().describe('왜 바꾸는지 한 줄'),
  title: z.string().max(200).optional().describe('없으면 "find → replace"'),
  exceptRowIds: z.array(z.number().int()).max(500).optional().describe('바꾸지 않을 행 id'),
}
const proposalStatusShape = { proposalId: z.number().int() }
const gameRefShape = {
  gameId: z.number().int().optional().describe('게임 id (/games/{gameId}/review)'),
  projectId: z.number().int().optional().describe('파일(프로젝트) id (/review/{projectId})'),
}
const getGlossaryShape = { ...gameRefShape, q: z.string().optional().describe('find·ko·note에 든 것만 (대소문자 무시)') }
const listFilesShape = { gameId: z.number().int().describe('게임 id (/games/{gameId}/…)') }
const inspectFileShape = {
  projectId: z.number().int().describe('파일(프로젝트) id (list_files의 projectId)'),
  offset: z.number().int().min(0).optional().describe('몇 번째 시트부터 (0부터, 기본 0)'),
  limit: z.number().int().min(1).optional().describe('시트 수 (기본 30, 최대 50)'),
}
const normalizeSheetsShape = {
  projectId: z.number().int().describe('파일(프로젝트) id (list_files의 projectId)'),
  sheets: z
    .array(
      z.object({
        sheetName: z.string().describe('시트 이름'),
        headerRow: z.number().int().min(0).optional().describe('헤더 행 번호(1부터). 0이면 헤더 없음. 생략하면 1'),
        keyCol: z.string().optional().describe('키 열 글자(예: A). 없으면 생략'),
        pairs: z
          .array(
            z.object({
              srcCol: z.string().describe('원문 열 글자'),
              enCol: z.string().optional().describe('영어 열 글자'),
              koCol: z.string().optional().describe('기존 한국어 열 글자. 없으면 새 열에 쓴다'),
            }),
          )
          .min(1)
          .max(8),
        srcToSimplified: z.boolean().optional().describe('원문 번체를 간체로 바꿔 읽기'),
      }),
    )
    .min(1)
    .max(40),
  replace: z.boolean().optional().describe('이미 정규화한 시트를 다시 만들기(번역·검수가 없는 시트만)'),
}
const undoShape = { activityIds: z.array(z.number().int()).min(1).max(50) }

export type FindRowsInput = z.infer<z.ZodObject<typeof findRowsShape>>
export type SaveRowsInput = z.infer<z.ZodObject<typeof saveRowsShape>>
export type SetNoteInput = z.infer<z.ZodObject<typeof setNoteShape>>
export type ProposeRowsInput = z.infer<z.ZodObject<typeof proposeRowsShape>>
export type ProposeReplaceInput = z.infer<z.ZodObject<typeof proposeReplaceShape>>
export type ProposalStatusInput = z.infer<z.ZodObject<typeof proposalStatusShape>>
export type GameRefInput = z.infer<z.ZodObject<typeof gameRefShape>>
export type GetGlossaryInput = z.infer<z.ZodObject<typeof getGlossaryShape>>
export type UndoInput = z.infer<z.ZodObject<typeof undoShape>>
export type ListFilesInput = z.infer<z.ZodObject<typeof listFilesShape>>
export type InspectFileInput = z.infer<z.ZodObject<typeof inspectFileShape>>
export type NormalizeSheetsInput = z.infer<z.ZodObject<typeof normalizeSheetsShape>>

const ok = (out: unknown): CallToolResult => ({ content: [{ type: 'text', text: JSON.stringify(out) }] })
const fail = (message: string): CallToolResult => ({ isError: true, content: [{ type: 'text', text: message }] })

async function guard(fn: () => Promise<CallToolResult>): Promise<CallToolResult> {
  try {
    return await fn()
  } catch (e) {
    if (e instanceof BackendError) return fail(e.message)
    throw e
  }
}

export function listGames(ctx: ToolContext): Promise<CallToolResult> {
  return guard(async () => ok((await ctx.backend.games()).map((g) => ({ id: g.id, name: g.name }))))
}

const MAX_GLOSSARY = 500
const NEED_GAME = 'gameId 또는 projectId 중 하나는 꼭 넣어야 합니다.'

async function resolveGameId(ctx: ToolContext, input: GameRefInput): Promise<number | null> {
  if (input.gameId !== undefined) return input.gameId
  if (input.projectId !== undefined) return (await ctx.backend.project(input.projectId)).gameId
  return null
}

export function readGameMemo(ctx: ToolContext, input: GameRefInput): Promise<CallToolResult> {
  return guard(async () => {
    const gameId = await resolveGameId(ctx, input)
    if (gameId === null) return fail(NEED_GAME)
    const m = await ctx.backend.memo(gameId)
    return ok({ gameId, updatedAt: m.updatedAt, text: memoToText(m.content) })
  })
}

export function getGlossary(ctx: ToolContext, input: GetGlossaryInput): Promise<CallToolResult> {
  return guard(async () => {
    const gameId = await resolveGameId(ctx, input)
    if (gameId === null) return fail(NEED_GAME)
    const [allR, candsR] = await Promise.allSettled([ctx.backend.glossary(gameId), ctx.backend.candidates(gameId)])
    if (allR.status === 'rejected') throw allR.reason
    const all = allR.value
    const cands = candsR.status === 'fulfilled' ? candsR.value : []
    const q = input.q?.toLowerCase()
    const hit = q
      ? all.filter((e) => [e.find, e.ko, e.note].some((v) => v != null && v.toLowerCase().includes(q)))
      : all
    return ok({
      entries: hit
        .slice(0, MAX_GLOSSARY)
        .map((e) => ({ kind: e.kind, matchMode: e.matchMode, find: e.find, ko: e.ko, note: e.note, shared: e.shared })),
      pending: cands.map((c) => ({ src: c.src, ko: c.ko, oldKo: c.oldKo, rows: c.rows, by: c.createdByName, at: c.createdAt })),
      total: hit.length,
      truncated: hit.length > MAX_GLOSSARY,
    })
  })
}

export function findRows(ctx: ToolContext, input: FindRowsInput): Promise<CallToolResult> {
  return guard(async () => {
    if (input.gameId === undefined && input.projectId === undefined) {
      return fail('gameId 또는 projectId 중 하나는 꼭 넣어야 합니다.')
    }
    const gameId = input.gameId ?? (await ctx.backend.project(input.projectId!)).gameId
    const sheets = (await ctx.backend.sheets(gameId)).filter(
      (s) =>
        (input.projectId === undefined || s.projectId === input.projectId) &&
        (input.sheet === undefined || s.sheet === input.sheet),
    )
    if (sheets.length === 0) return fail('그 파일·시트를 찾을 수 없습니다.')

    const page = input.page ?? 0
    const size = Math.min(input.size ?? DEFAULT_SIZE, MAX_SIZE)
    const fileOf = new Map<string, string>()
    for (const s of sheets) fileOf.set(`${s.projectId}\n${s.sheet}`, s.fileName)

    const lanes: unknown[] = []
    const laneDefs: [string, SheetInfo[]][] = [
      ['final', sheets.filter((s) => s.finalReview)],
      ['normal', sheets.filter((s) => !s.finalReview)],
    ]
    for (const [lane, laneSheets] of laneDefs) {
      if (laneSheets.length === 0) continue
      const r = await ctx.backend.rows(gameId, {
        sheets: laneSheets.map((s) => s.configId),
        q: input.q,
        qIn: input.qIn,
        filter: input.filter,
        page,
        size,
      })
      lanes.push({ lane, total: r.total, rows: r.rows.map((row) => toRow(row, fileOf)) })
    }
    return ok({ page, size, lanes })
  })
}

const MAX_TEXT = 300

function cut(v: string | null): [string | null, boolean] {
  return v != null && v.length > MAX_TEXT ? [v.slice(0, MAX_TEXT) + '…', true] : [v, false]
}

function toRow(r: BackendRow, fileOf: Map<string, string>) {
  const [src, a] = cut(r.src)
  const [existingKo, b] = cut(r.existingKo)
  const [note, d] = cut(r.note)
  const truncated = a || b || d
  return {
    rowId: r.id,
    file: fileOf.get(`${r.projectId}\n${r.sheet}`) ?? '',
    sheet: r.sheet,
    index: r.indexLabel,
    src,
    existingKo,
    fix: r.fix,
    confirmed: r.confirmed,
    needsCheck: r.needsCheck,
    finalMark: r.finalMark,
    note,
    dupCount: r.dupCount,
    ...(truncated ? { truncated: true } : {}),
  }
}

export function saveRows(ctx: ToolContext, input: SaveRowsInput): Promise<CallToolResult> {
  return guard(async () => {
    const r = await ctx.backend.saveBatch(
      ctx.reviewerId,
      input.items.map((i) => ({ segmentId: i.rowId, text: i.text, base: i.base })),
    )
    return ok({
      saved: r.saved,
      unchanged: r.unchanged,
      skipped: r.skippedRows.map((s) => ({ rowId: s.id, reason: s.reason })),
      propagated: r.propagated,
      finalMarked: r.finalMarked,
      activityIds: r.activityIds,
    })
  })
}

export function setNote(ctx: ToolContext, input: SetNoteInput): Promise<CallToolResult> {
  return guard(async () => {
    const r = await ctx.backend.notes(
      ctx.reviewerId,
      input.items.map((i) => ({
        segmentId: i.rowId,
        note: i.note,
        needsCheck: i.needsCheck,
        mode: i.mode === undefined ? undefined : i.mode === 'append' ? ('APPEND' as const) : ('REPLACE' as const),
      })),
    )
    return ok(r.map((n) => ({ rowId: n.segmentId, before: n.before, after: n.after, needsCheck: n.needsCheck })))
  })
}

export function undo(ctx: ToolContext, input: UndoInput): Promise<CallToolResult> {
  return guard(async () => {
    const r = await ctx.backend.undo(ctx.reviewerId, input.activityIds)
    return ok({ reverted: r.reverted, skipped: r.skipped, activityIds: r.activityIds })
  })
}

export function proposeRows(ctx: ToolContext, input: ProposeRowsInput): Promise<CallToolResult> {
  return guard(async () => {
    const r = await ctx.backend.propose(
      ctx.reviewerId,
      input.title,
      input.items.map((i) =>
        i.base === undefined
          ? { segmentId: i.rowId, text: i.text, base: null, autoBase: true, reason: i.reason }
          : { segmentId: i.rowId, text: i.text, base: i.base, reason: i.reason },
      ),
    )
    return ok({
      proposalId: r.proposalId,
      presentNow: r.presentNow,
      locked: r.lockedRows.map((l) => ({ rowId: l.id, reason: l.reason })),
    })
  })
}

export function proposeReplace(ctx: ToolContext, input: ProposeReplaceInput): Promise<CallToolResult> {
  return guard(async () => {
    if (input.gameId === undefined && input.projectId === undefined) return fail(NEED_GAME)
    const r = await ctx.backend.replace(ctx.reviewerId, input)
    return ok({
      proposalId: r.proposalId,
      presentNow: r.presentNow,
      matched: r.matched,
      proposed: r.proposed,
      locked: r.locked,
      srcMissing: r.srcMissing,
      samples: r.samples.map((x) => ({ rowId: x.rowId, before: x.before, after: x.after })),
      termCandidate: r.termCandidate,
    })
  })
}

export function proposalStatus(ctx: ToolContext, input: ProposalStatusInput): Promise<CallToolResult> {
  return guard(async () => {
    const p = await ctx.backend.proposal(input.proposalId)
    if (p.reviewerId !== ctx.reviewerId) return fail('다른 계정의 제안입니다.')
    const r = p.result
    return ok({
      status: p.status,
      title: p.title,
      saved: r?.saved ?? 0,
      unchanged: r?.unchanged ?? 0,
      propagated: r?.propagated ?? 0,
      excluded: r?.excluded ?? [],
      skipped: (r?.skippedRows ?? []).map((s) => ({ rowId: s.id, reason: s.reason })),
      finalMarked: r?.finalMarked ?? 0,
      activityIds: r?.activityIds ?? [],
    })
  })
}

const INSPECT_DEFAULT = 30
const INSPECT_MAX = 50

export function listFiles(ctx: ToolContext, input: ListFilesInput): Promise<CallToolResult> {
  return guard(async () => ok(await ctx.backend.files(input.gameId)))
}

export function inspectFile(ctx: ToolContext, input: InspectFileInput): Promise<CallToolResult> {
  return guard(async () =>
    ok(await ctx.backend.inspect(input.projectId, input.offset ?? 0, Math.min(input.limit ?? INSPECT_DEFAULT, INSPECT_MAX))),
  )
}

export function normalizeSheets(ctx: ToolContext, input: NormalizeSheetsInput): Promise<CallToolResult> {
  return guard(async () => {
    const { projectId, ...body } = input
    return ok(await ctx.backend.normalizeSheets(ctx.reviewerId, projectId, body))
  })
}

export function registerTools(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'list_games',
    {
      title: '게임 목록',
      description:
        '검수 화면 주소(/games/{gameId}/… 또는 /review/{projectId})가 보이면 부르지 말고 그 id를 바로 쓴다. 주소를 모를 때만 쓴다. 게임 id와 이름을 돌려준다.',
      annotations: { readOnlyHint: true },
    },
    () => listGames(ctx),
  )
  server.registerTool(
    'find_rows',
    {
      title: '행 찾기',
      description:
        '검수 화면 주소 /games/{gameId}/review 이면 gameId, /review/{projectId} 이면 projectId를 넣는다. 결과는 레인별(final=최종 리뷰, normal=일반)로 나뉜다. 저장할 때 base에는 여기서 본 fix를 그대로 넣는다. 원문·기존 한국어·비고가 300자를 넘으면 잘리고(truncated: true) fix는 자르지 않는다.',
      inputSchema: findRowsShape,
      annotations: { readOnlyHint: true },
    },
    (args) => findRows(ctx, args),
  )
  server.registerTool(
    'save_rows',
    {
      title: '수정 저장',
      description:
        "저장 전에 사용자에게 바꾸기 전·후를 보여 주고 허락을 받아라. 확정·제외 행과 그 사이 다른 사람이 고친 행은 건너뛴다(skipped). 이미 같은 글인 행은 unchanged로 센다. 최종 리뷰 시트의 행은 '최종 수정'도 켜진다. 같은 원문 행도 함께 바뀐다. 사용자가 웹 검수 화면을 열어 두고 있으면 propose_rows를 먼저 쓴다.",
      inputSchema: saveRowsShape,
      annotations: { readOnlyHint: false, destructiveHint: true },
    },
    (args) => saveRows(ctx, args),
  )
  server.registerTool(
    'set_note',
    {
      title: '비고·확인 필요 표시',
      description:
        '기본은 기존 비고 뒤에 붙인다. undo는 비고를 되돌리지 않는다. 비고를 되돌리려면 before 값으로 mode=replace를 쓴다.',
      inputSchema: setNoteShape,
      annotations: { readOnlyHint: false, destructiveHint: false },
    },
    (args) => setNote(ctx, args),
  )
  server.registerTool(
    'undo',
    {
      title: '되돌리기',
      description:
        'save_rows나 proposal_status가 돌려준 activityIds를 넣는다(한 번에 50개까지). 돌려받은 activityIds로 다시 부르면 다시 실행된다. 이 연결로 직접 고친 것만 되돌릴 수 있다.',
      inputSchema: undoShape,
      annotations: { readOnlyHint: false, destructiveHint: true },
    },
    (args) => undo(ctx, args),
  )
  server.registerTool(
    'propose_rows',
    {
      title: '제안 보내기',
      description:
        '고칠 안을 사용자의 웹 검수 화면에 확인 창으로 보낸다. 사용자가 웹 검수 화면에서 행을 체크해 저장한다. 채팅에 전·후 표를 길게 보여 주지 말고 무엇을 왜 바꿨는지만 짧게 말한다. presentNow가 false면 그 게임 검수 화면을 열면 창이 뜬다고 알려 준다. 결과는 proposal_status로 확인한다.',
      inputSchema: proposeRowsShape,
      annotations: { readOnlyHint: false, destructiveHint: false },
    },
    (args) => proposeRows(ctx, args),
  )
  server.registerTool(
    'propose_replace',
    {
      title: '찾아 바꾸기 제안',
      description:
        '행마다 문장을 쓰지 말고 이걸 먼저 쓴다. 지금 한국어(fix)에서 find를 모두 replace로 바꾸는 제안을 만든다. 서버가 행을 찾고 뒤 조사를 맞춘다. find가 한글로 끝나지 않으면(예: HP) 조사는 맞추지 않으니 체크 창에서 확인하라고 알린다. find·replace는 조사 없는 낱말로 넣는다. 직접 저장하지 않고 항상 사용자의 웹 검수 화면에 확인 창으로 뜬다. 용어를 바꾸는 거면 find_rows로 몇 행의 원문을 보고 그 한국어가 옮긴 원문 용어를 src에 넣는다(단어장 후보가 된다). 문체 수정이면 src를 넣지 않는다. 단어장(get_glossary)과 다르게 바꾸려면 먼저 사용자에게 알린다. 채팅에는 몇 행을 왜 바꾸는지만 짧게 말한다. gameId 또는 projectId 중 하나는 꼭 넣는다. 결과는 proposal_status로 확인한다.',
      inputSchema: proposeReplaceShape,
      annotations: { readOnlyHint: false, destructiveHint: false },
    },
    (args) => proposeReplace(ctx, args),
  )
  server.registerTool(
    'proposal_status',
    {
      title: '제안 결과',
      description:
        'propose_rows나 propose_replace로 보낸 제안의 결과를 돌려준다: status(PENDING 대기·APPLIED 저장함·DISMISSED 넣지 않음·EXPIRED 24시간 지나 만료), 저장한 행 수, 사람이 체크를 뺀 행(excluded), 서버가 건너뛴 행과 이유(skipped), 되돌리기용 activityIds(undo에 넣는다).',
      inputSchema: proposalStatusShape,
      annotations: { readOnlyHint: true },
    },
    (args) => proposalStatus(ctx, args),
  )
  server.registerTool(
    'read_game_memo',
    {
      title: '게임 메모 읽기',
      description:
        '이 게임 작업을 시작할 때 먼저 읽는다. 고객 요청·문체 규칙이 있으면 따른다. 검수 화면 주소의 id를 그대로 넣는다.',
      inputSchema: gameRefShape,
      annotations: { readOnlyHint: true },
    },
    (args) => readGameMemo(ctx, args),
  )
  server.registerTool(
    'get_glossary',
    {
      title: '단어장 읽기',
      description:
        '바꾸기·제안 전에 읽는다. entries가 정해진 표기다. pending은 다른 사람이 정해 승인 대기 중인 것 — 참고하고, 다르게 바꾸려면 사용자에게 알린다.',
      inputSchema: getGlossaryShape,
      annotations: { readOnlyHint: true },
    },
    (args) => getGlossary(ctx, args),
  )
  server.registerTool(
    'list_files',
    {
      title: '파일 목록',
      description:
        '게임에 올린 파일 목록과 파일마다 정규화한 시트 수를 돌려준다. 정규화를 시작할 때 먼저 부른다. 파일 올리기는 사용자가 게임 화면에서 한다.',
      inputSchema: listFilesShape,
      annotations: { readOnlyHint: true },
    },
    (args) => listFiles(ctx, args),
  )
  server.registerTool(
    'inspect_file',
    {
      title: '시트 들여다보기',
      description:
        '파일의 시트마다 맨 위 6행(top — 헤더라고 가정하지 않은 원래 행)과 열별 글자 비율(columns: filled·han·hangul·latin·traditional, %)을 돌려준다. 헤더 이름보다 columns를 믿어라: 원문 열은 han이 높은 열, 한국어 열은 hangul이 높은 열이다. top을 보고 헤더 행 번호를 정한다(헤더가 없으면 0). 시트가 많으면 offset·limit으로 나눠 부른다(한 번에 최대 50). canReplace가 null이 아닌 시트는 이미 정규화한 것이다(true면 번역·검수가 없어 다시 정규화할 수 있고, false면 할 수 없다).',
      inputSchema: inspectFileShape,
      annotations: { readOnlyHint: true },
    },
    (args) => inspectFile(ctx, args),
  )
  server.registerTool(
    'normalize_sheets',
    {
      title: '시트 정규화',
      description:
        '부르기 전에 inspect_file로 시트를 보고, 계획을 같은 모양끼리 묶은 표(시트 수, 원문 열, 한국어 열, 간체 변환, 예시 시트 이름)로 사용자에게 보여 주고 허락을 받아라. 헷갈리는 시트(원문 후보가 둘, 한자 비율이 낮음, 헤더가 이상함)는 따로 물어라. 한국어 열이 둘(기존과 수정본)로 보이면 최종 리뷰 파일일 수 있으니 정규화하지 말고 웹 정규화 화면에서 하라고 안내한다. 간체 열과 번체 열이 함께 있으면 간체 열을 원문으로 고른다. 원문 열에 traditional이 0보다 크면 srcToSimplified를 켠다. 한 번에 40개 시트까지 — 더 많으면 나눠 부른다. 결과의 status가 NOT_RUN인 시트는 시간이 모자라 하지 않은 것이니 그 시트부터 다시 부른다. 결과의 warnings는 빠짐없이 사용자에게 전한다. "원문이 빈 N행은 X열에 글이 있습니다" 경고가 오면 inspect_file 결과로 X열을 확인해, X열이 같은 원문(예: 같은 줄의 번체판)일 때만 그 시트를 X열 + srcToSimplified로 replace: true를 줘서 다시 정규화한다. 다른 글(설명·메모 등)이면 다시 하지 말고 그대로 두고 사용자에게 알린다. REFUSED·FAILED는 이유를 전한다. replace는 번역·검수가 하나도 없는 시트에만 통한다. 다 끝나면 /games/{gameId}/review 에서 한 표로 검수할 수 있다고 알려 준다.',
      inputSchema: normalizeSheetsShape,
      annotations: { readOnlyHint: false, destructiveHint: false },
    },
    (args) => normalizeSheets(ctx, args),
  )
}
