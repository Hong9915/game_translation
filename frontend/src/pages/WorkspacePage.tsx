import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ApiError, api } from '../api/client'
import { useProject } from '../api/hooks'
import type { GlossaryKind, MarkKey, MarksPatch, ReviewerRef, RowFilter, SegmentRow, WorkspaceRow, WorkspaceRowsResponse } from '../api/types'
import { ReviewerNameModal } from '../components/workspace/ReviewerNameModal'
import ActivityPanel from '../components/workspace/ActivityPanel'
import GlossaryPanel, { type GlossaryPanelProps } from '../components/workspace/GlossaryPanel'
import { JosaApplyDialog } from '../components/workspace/JosaApplyDialog'
import { TagSpacingDialog } from '../components/workspace/TagSpacingDialog'
import { CommandFixDialog } from '../components/workspace/CommandFixDialog'
import { CommandRunButton, CommandRunNotice } from '../components/workspace/command/CommandRunButton'
import { useCommandRun } from '../components/workspace/command/commandRuns'
import { SheetBar } from '../components/workspace/SheetBar'
import { StatusBar, type SaveState } from '../components/workspace/StatusBar'
import { TopBar } from '../components/workspace/TopBar'
import { PageBar, ViewSizePicker, type ViewSize } from '../components/workspace/PageBar'
import { ROW_HEIGHT, WorkspaceGrid, type GridPosition } from '../components/workspace/WorkspaceGrid'
import { useLinger } from '../hooks/useLinger'
import { usePresence } from '../hooks/usePresence'
import { useReviewer } from '../hooks/useReviewer'
import { useWorkspaceEvents } from '../hooks/useWorkspaceEvents'
import type { Cell } from '../lib/gridNav'
import { josaRefetchInterval } from '../lib/josa'
import { applyMarks, rollbackMarks, rowFromSegment, withMarksOf } from '../lib/workspaceCache'

const PAGE = 500
/** 오른쪽 패널 폭과 여닫는 전환 시간. 패널 자체(w-[380px])와 같아야 한다. */
const PANEL_WIDTH = 380
const PANEL_MS = 200
/** 변경 내역에서 행으로 갈 때 그 행 위로 보여 줄 행 수. */
const JUMP_ROWS_ABOVE = 8
const RATIO = 1.0
// 보기(전체 스크롤 / 50·100행 쪽 넘기기). 브라우저마다 기억한다 — 저장소가 막혀도 기본값(100행)으로 동작한다.
const VIEW_SIZE_KEY = 'gametrans.workspaceViewSize'
const loadViewSize = (): ViewSize => {
  try { const v = localStorage.getItem(VIEW_SIZE_KEY); return v === 'all' ? 'all' : v === '50' ? 50 : 100 } catch { return 100 }
}
const storeViewSize = (v: ViewSize) => { try { localStorage.setItem(VIEW_SIZE_KEY, String(v)) } catch { /* 기억만 못 한다 */ } }

/** 검수 전용 프로젝트의 엑셀식 검수 화면(설계 §1.2). 조회·저장은 여기서, 표시·키 처리는 WorkspaceGrid가 한다. */
export function WorkspacePage() {
  const id = Number(useParams<{ id: string }>().id)
  const qc = useQueryClient()
  const project = useProject(id).data
  const { reviewer, setName } = useReviewer()
  const [renaming, setRenaming] = useState(false)
  // 오른쪽 패널은 하나만 연다(설계 2026-09-30 §4.2) — 둘 다 380px이라 같이 열면 그리드가 너무 좁아진다.
  const [sidePanel, setSidePanel] = useState<'glossary' | 'activity' | null>(null)
  const glossaryOpen = sidePanel === 'glossary'
  const activityOpen = sidePanel === 'activity'
  // 닫을 때 폭 전환(PANEL_MS)이 끝날 때까지 패널을 남겨 둔다. seq는 다시 열 때 새로 마운트하는 key — 남아 있던 옛 폼이 보이지 않게.
  const { shown: shownPanel, seq: panelSeq } = useLinger(sidePanel, PANEL_MS)
  const [glossaryInitial, setGlossaryInitial] = useState<GlossaryPanelProps['initial']>(undefined)
  const [sheet, setSheet] = useState<string | null>(null)
  const [filter, setFilter] = useState<RowFilter>('ALL')
  const [q, setQ] = useState('')
  // ×N 칩으로 같은 원문만 모아 보는 중이면 그 원문. 주소(?dup=)에 두어서 브라우저 뒤로 가기로 풀리고 새로 고침해도 남는다.
  const [params, setParams] = useSearchParams()
  const dup = params.get('dup')
  const navigate = useNavigate()
  const location = useLocation()
  const [range, setRange] = useState<[number, number]>([0, 0])
  const [viewSize, setViewSize] = useState<ViewSize>(loadViewSize)
  // 쪽 넘기기 보기에서 지금 쪽(0부터). 전체 스크롤 보기에서는 쓰지 않는다.
  const [viewPage, setViewPage] = useState(0)
  const paged = viewSize !== 'all'
  const [busy, setBusy] = useState<Set<number>>(new Set())
  const [translating, setTranslating] = useState<Set<number>>(new Set())
  const [saveState, setSaveState] = useState<SaveState>('saved')
  const [propagated, setPropagated] = useState(0)
  // 저장할 때마다 늘어나는 값 — propagated 건수가 지난번과 같아도(2행→2행) StatusBar가 다시 4초를 보여주게 한다.
  const [propagatedSeq, setPropagatedSeq] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [selection, setSelection] = useState('')
  const [editing, setEditing] = useState<number | null>(null)
  // 저장 실패로 아직 반영 안 된 입력(설계 §5) — 성공하면 지우고, 실패하면 쌓아 둔다(다른 셀 실패가 서로 안 지운다).
  // base는 실패할 때의 r.fix — 그 뒤 이 행이 다른 곳(SSE·다른 사람의 저장)에서 base와 다른 값으로 바뀌면
  // 이 실패 표시는 더는 최신이 아니므로 지운다(안 그러면 새 값이 숨고, 재편집이 옛 값을 다시 덮어쓴다).
  const [failedText, setFailedText] = useState<Map<number, { text: string; base: string }>>(new Map())
  const reconcileFailed = useCallback((rid: number, fix: string | null) => {
    setFailedText((prev) => {
      const entry = prev.get(rid)
      if (!entry || (fix ?? '').trim() === entry.base.trim()) return prev
      const next = new Map(prev); next.delete(rid); return next
    })
  }, [])

  const summary = useQuery({ queryKey: ['workspace-summary', id, sheet], queryFn: () => api.workspace.summary(id, sheet ?? undefined),
    refetchInterval: (q) => josaRefetchInterval(q.state.data) })
  const { highlighted, presence, setPresence, status } = useWorkspaceEvents(id, reviewer, reconcileFailed)
  usePresence(id, reviewer, editing, setPresence, sheet)

  // 첫 시트를 요약에서 고른다(렌더 단계 보정 — ReviewPage의 시트 탭 보정과 같은 방식)
  const sheets = summary.data?.sheets ?? []
  if (sheet === null && sheets.length > 0) setSheet(sheets[0].sheet)

  // 전체 스크롤 보기: 보이는 구간이 걸치는 500행 페이지들만 받는다. 쪽 넘기기 보기: 지금 쪽 하나만 그 크기로 받는다.
  // 두 보기 모두 키가 ['workspace-rows', id, ...]로 시작해서 저장·SSE의 캐시 반영(patch 등)이 똑같이 걸린다.
  const size = paged ? viewSize : PAGE
  const firstPage = paged ? viewPage : Math.floor(range[0] / PAGE)
  const lastPage = paged ? viewPage : Math.max(firstPage, Math.floor(Math.max(0, range[1] - 1) / PAGE))
  const pages = useMemo(() => Array.from({ length: lastPage - firstPage + 1 }, (_, i) => firstPage + i), [firstPage, lastPage])
  const pageQueries = useQueries({
    queries: pages.map((page) => ({
      queryKey: ['workspace-rows', id, sheet, filter, q, size, page],
      queryFn: () => api.workspace.rows(id, { sheet: sheet as string, page, size, filter, q }),
      enabled: sheet !== null,
      placeholderData: (prev: WorkspaceRowsResponse | undefined) => prev,
    })),
  })
  const total = pageQueries.find((p) => p.data)?.data?.total ?? 0
  // pageQueries는 매 렌더 새 배열이라 이걸 그대로 deps에 넣으면 스크롤할 때마다(50k 행 기준 핫루프) 아래 fill(total)이 돈다.
  // 응답이 실제로 바뀐 시점(dataUpdatedAt)만 바뀔 때 다시 계산한다.
  const dataVersion = pageQueries.map((p) => p.dataUpdatedAt).join(',')
  const rows = useMemo(() => {
    // 쪽 넘기기 보기는 그 쪽의 행만 그리드에 준다. 옛 쪽(placeholderData)이 새 쪽 자리에 보이지 않게 응답의 page를 확인한다.
    if (paged) {
      const hit = pageQueries[0]?.data
      return hit && hit.page === viewPage ? hit.rows : []
    }
    const out: (WorkspaceRow | undefined)[] = new Array(total).fill(undefined)
    // 각 관찰자(observer)는 배열 순서(useQueries 인덱스)로만 매치된다 — placeholderData가 옛 페이지의
    // "데이터"를 들고 있는 동안 키가 다른 페이지로 바뀌면 i번째 관찰자가 더 이상 pages[i]의 응답이 아닐 수
    // 있다. 응답 자체가 보고하는 page(RowsResponse.page)로 색인해야 옛 데이터가 제자리(자기 페이지)에만
    // 얹히고, 화면에 보이는 구간은 아직 못 받은 자리라 '…' placeholder로 남는다.
    pageQueries.forEach((p) => { p.data?.rows.forEach((r, j) => { out[p.data!.page * PAGE + j] = r }) })
    return out
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [total, dataVersion, paged, viewPage, size])
  const failed = pageQueries.some((p) => p.isError)

  const onVisibleRange = useCallback((start: number, end: number) => setRange((prev) => (prev[0] === start && prev[1] === end ? prev : [start, end])), [])

  // 저장·완료 응답(SegmentRow)을 목록 캐시의 그 행에 바로 반영한다 — 목록을 다시 받지 않는다
  const patch = (seg: SegmentRow) => {
    for (const [key, data] of qc.getQueriesData<WorkspaceRowsResponse>({ queryKey: ['workspace-rows', id] })) {
      if (data?.rows.some((r) => r.id === seg.id)) qc.setQueryData(key, { ...data, rows: data.rows.map((r) => (r.id === seg.id ? rowFromSegment(r, seg) : r)) })
    }
    qc.invalidateQueries({ queryKey: ['workspace-summary', id] })
    reconcileFailed(seg.id, seg.currentText)
  }
  // saveState는 저장 뮤테이션 전용 표시다 — 내보내기·담당 배정 실패는 배너로만 보여주고 "저장 안 됨"을 주장하지 않는다.
  const showError = (e: unknown) => {
    const message = e instanceof Error ? e.message : '요청에 실패했습니다.'
    setError(e instanceof ApiError && e.status === 400 && message.startsWith('모르는 검토자') ? '이름을 다시 입력해 주세요.' : message)
  }
  const toggle = (rid: number, on: boolean) => (prev: Set<number>) => { const next = new Set(prev); if (on) next.add(rid); else next.delete(rid); return next }
  const mark = (rid: number, on: boolean) => setBusy(toggle(rid, on))
  const setFailed = (rid: number, text: string | null, base?: string) => setFailedText((prev) => {
    if (text === null) { if (!prev.has(rid)) return prev; const next = new Map(prev); next.delete(rid); return next }
    const next = new Map(prev); next.set(rid, { text, base: base ?? '' }); return next
  })

  const save = useMutation({
    mutationFn: ({ row, text }: { row: WorkspaceRow; text: string }) => api.saveTranslation(row.id, text, RATIO),
    onMutate: ({ row }) => { mark(row.id, true); setSaveState('saving') },
    onSuccess: (res, { row }) => {
      patch(res.row); setSaveState('saved'); setError(null); setFailed(row.id, null)
      setPropagated(res.propagated); setPropagatedSeq((s) => s + 1)
      // 원문이 같아 함께 바뀐 행은 다른 페이지·다른 시트에도 있을 수 있어 목록 캐시를 다시 받는다.
      if (res.propagated > 0) qc.invalidateQueries({ queryKey: ['workspace-rows', id] })
    },
    // base = 편집을 시작한 그 시점의 r.fix — 이 값과 달라지면(다른 곳에서 행이 바뀌면) reconcileFailed가 이 항목을 지운다.
    onError: (e, { row, text }) => { setFailed(row.id, text, row.fix ?? ''); setSaveState('error'); showError(e) },
    onSettled: (_d, _e, { row }) => mark(row.id, false),
  })
  // 그리드 한 행(WorkspaceRow)을 캐시에서 통째로 바꾼다 — 행 번역·체크 응답이 이 모양이다.
  const replaceRow = (next: WorkspaceRow) => {
    for (const [key, data] of qc.getQueriesData<WorkspaceRowsResponse>({ queryKey: ['workspace-rows', id] })) {
      if (data?.rows.some((r) => r.id === next.id)) qc.setQueryData(key, { ...data, rows: data.rows.map((r) => (r.id === next.id ? next : r)) })
    }
  }
  // 원문 우클릭 → GPT 번역. 응답이 그리드 한 행(WorkspaceRow) 그대로라 캐시의 그 행을 통째로 바꾼다.
  const translate = useMutation({
    mutationFn: ({ row, instructions }: { row: WorkspaceRow; instructions: string }) => api.translateRow(row.id, instructions),
    onMutate: ({ row }) => setTranslating(toggle(row.id, true)),
    onSuccess: (next) => {
      replaceRow(next)
      qc.invalidateQueries({ queryKey: ['workspace-summary', id] })
      // 저장 실패로 남아 있던 입력은 방금 덮어쓴 GPT 결과를 가리므로 지운다
      setFailed(next.id, null)
      setError(null)
    },
    onError: showError,
    onSettled: (_d, _e, { row }) => setTranslating(toggle(row.id, false)),
  })
  // 캐시에 있는 그 행을 fn으로 고친다(캐시의 최신 행을 기준으로 — 클릭 때 들고 있던 행은 그 사이 낡았을 수 있다).
  const updateRow = (rid: number, fn: (r: WorkspaceRow) => WorkspaceRow) => {
    for (const [key, data] of qc.getQueriesData<WorkspaceRowsResponse>({ queryKey: ['workspace-rows', id] })) {
      if (data?.rows.some((r) => r.id === rid)) qc.setQueryData(key, { ...data, rows: data.rows.map((r) => (r.id === rid ? fn(r) : r)) })
    }
  }
  // 1차·2차·확인 필요·확정·비고. 누르자마자 캐시에 반영하고(낙관적), 응답은 체크·비고 칸만 반영한다 — 행 전체로 바꾸면 늦게 온
  // 응답이 그 사이 저장한 Fix를 되돌린다. 실패하면 이 요청이 바꾼 칸만, 아직 그 값일 때 되돌린다(rollbackMarks).
  // 다른 사람의 체크는 SSE marks 이벤트가 맞춘다.
  // 행마다 마지막 체크 요청 번호. 같은 칸을 빠르게 두 번 누르면 응답 순서가 뒤바뀔 수 있어, 마지막 요청의 응답만 반영한다.
  const marksSeq = useRef(new Map<number, number>())
  const marks = useMutation({
    mutationFn: ({ row, patch }: { row: WorkspaceRow; patch: MarksPatch }) => api.setMarks(row.id, patch),
    onMutate: ({ row, patch }) => {
      const seq = (marksSeq.current.get(row.id) ?? 0) + 1
      marksSeq.current.set(row.id, seq)
      let prev = row
      updateRow(row.id, (r) => { prev = r; return applyMarks(r, patch) })
      return { prev, seq }
    },
    onSuccess: (next, _v, ctx) => {
      if (ctx && marksSeq.current.get(next.id) === ctx.seq) updateRow(next.id, (r) => withMarksOf(r, next))
      qc.invalidateQueries({ queryKey: ['workspace-summary', id] }); setError(null)
    },
    onError: (e, { row, patch }, ctx) => { if (ctx) updateRow(row.id, (r) => rollbackMarks(r, ctx.prev, patch)); showError(e) },
  })
  // 체크 머리글 더블클릭 일괄 체크. 쪽 보기는 화면에 보이는 행의 id를 그대로 보낸다 — 서버가 쪽을 다시 계산하면 그 사이
  // 하나씩 누른 체크로 필터 결과가 바뀌어 안 보이던 행이 끼어든다. 전체 보기는 목록과 같은 조건(시트·필터·검색)을 넘긴다.
  // 끝나면 목록·요약을 다시 읽는다.
  const bulkMarks = useMutation({
    mutationFn: ({ key, on }: { key: MarkKey; on: boolean }) =>
      api.workspace.bulkMarks(id, {
        sheet: sheet as string, [key]: on,
        ...(paged ? { ids: rows.flatMap((r) => (r ? [r.id] : [])) } : { filter, q }),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workspace-rows', id] })
      qc.invalidateQueries({ queryKey: ['workspace-summary', id] })
      setError(null)
    },
    onError: showError,
  })
  // 조사 배지 창의 적용·무시(설계 2026-10-07 §6). 끝나면 목록·요약을 다시 읽는다 — 새 제안은 다음 검사 차례에 생긴다.
  const refreshRows = () => {
    qc.invalidateQueries({ queryKey: ['workspace-rows', id] })
    qc.invalidateQueries({ queryKey: ['workspace-summary', id] })
  }
  const [josaDialog, setJosaDialog] = useState<{ x: number; y: number } | null>(null)
  const [tagSpacingOpen, setTagSpacingOpen] = useState(false)
  // 명령으로 고치기 창을 연 시트(null = 닫힘). 실행은 창 밖 보관소에서 돌아 창을 닫아도 계속된다.
  const [commandFixSheet, setCommandFixSheet] = useState<string | null>(null)
  const commandRun = useCommandRun(id)
  const josaApplyRow = useMutation({
    mutationFn: (row: WorkspaceRow) => api.workspace.josaApply(id, { segmentIds: [row.id], kinds: ['HANGUL', 'NUMBER', 'TAG'] }),
    onSuccess: () => { refreshRows(); setError(null) },
    onError: showError,
  })
  const josaIgnore = useMutation({
    mutationFn: (row: WorkspaceRow) => api.workspace.josaIgnore(id, row.id),
    onSuccess: () => { refreshRows(); setError(null) },
    onError: showError,
  })
  const exportXlsx = useMutation({
    mutationFn: async () => {
      const preview = await api.exportPreview(id, RATIO)
      const blob = await api.exportXlsx(id, { addFlagColumn: false, addHistorySheet: false, addGlossarySheet: false, ratio: RATIO })
      return { blob, name: preview.outputFileName }
    },
    onSuccess: ({ blob, name }) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = name
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 0)
      qc.invalidateQueries({ queryKey: ['project', id] })
    },
    onError: showError,
  })

  // 검색·모아 보기에 들어가기 전 자리(쪽·스크롤·고른 칸). 검색어가 비면 거기로 돌아간다 — 첫 행으로 튀지 않게.
  const position = useRef<GridPosition>({ cell: null, scrollTop: 0 })
  const onPosition = useCallback((p: GridPosition) => { position.current = p }, [])
  const beforeSearch = useRef<(GridPosition & { viewPage: number }) | null>(null)
  const [restore, setRestore] = useState<GridPosition | null>(null)
  const onRestored = useCallback(() => setRestore(null), [])
  const qRef = useRef(q)
  qRef.current = q
  const viewPageRef = useRef(viewPage)
  viewPageRef.current = viewPage
  // 검색어를 바꾸는 곳은 모두 이걸 거친다. 빈 검색어 → 검색이면 자리를 기억하고, 검색 → 빈 검색어면 되돌린다.
  const applyQ = useCallback((text: string) => {
    const prev = qRef.current
    if (prev === text) return
    setQ(text); setRange([0, 0])
    if (prev === '') {
      beforeSearch.current = { ...position.current, viewPage: viewPageRef.current }
      setViewPage(0); setRestore(null)
    } else if (text === '' && beforeSearch.current) {
      const { viewPage: page, ...pos } = beforeSearch.current
      beforeSearch.current = null
      setViewPage(page); setRestore(pos)
    } else {
      setViewPage(0)
    }
  }, [])
  // 시트·필터·보기를 바꾸면 목록이 달라지므로 기억한 자리는 버린다
  const forgetPosition = () => { beforeSearch.current = null; setRestore(null) }
  const pickSheet = (s: string) => { setSheet(s); setRange([0, 0]); setViewPage(0); forgetPosition() }
  // 변경 내역에서 행으로 가기(설계 §4.3). 검색·필터·모아 보기를 풀고 시트를 바꾼 뒤, 기존 restore 경로로 그 행의 Fix 칸을 고른다.
  // 쪽 보기면 순번으로 쪽을 계산한다. 행이 화면 위쪽에 딱 붙지 않게 8행 위에서부터 보이게 한다.
  // dup을 푸는 effect는 qRef.current가 옛 dup 원문일 때만 applyQ('')를 부르는데, 여기서 먼저 setQ('')를 해 두므로 restore가 덮어써지지 않는다.
  const jumpTo = useCallback((target: string, ordinal: number) => {
    beforeSearch.current = null
    if (dup !== null) setParams({}, { replace: true })
    setQ(''); setFilter('ALL'); setSheet(target); setRange([0, 0])
    const size = viewSize === 'all' ? null : viewSize
    const row = size === null ? ordinal : ordinal % size
    setViewPage(size === null ? 0 : Math.floor(ordinal / size))
    setRestore({ cell: { row, col: 'fix' }, scrollTop: Math.max(0, (row - JUMP_ROWS_ABOVE) * ROW_HEIGHT) })
  }, [dup, setParams, viewSize])
  const pickFilter = (f: RowFilter) => { setFilter(f); setRange([0, 0]); setViewPage(0); forgetPosition() }
  const search = useCallback((text: string) => {
    applyQ(text)
    // 모아 보기 중에 검색어를 직접 바꾸면 모아 보기는 끝난 것 — 주소에서 dup을 뺀다
    if (dup !== null && text !== dup) setParams({}, { replace: true })
  }, [dup, setParams, applyQ])
  // 칩을 누르면 기록을 하나 쌓아 뒤로 가기가 모아 보기만 푼다. 이미 모아 보는 중이면 바꿔 끼워 기록이 쌓이지 않게 한다.
  const filterDuplicates = useCallback((src: string) => {
    setParams({ dup: src }, { replace: dup !== null, state: { dupPushed: true } })
  }, [dup, setParams])
  // 칩이 쌓은 기록이면 뒤로 가서 없애고(뒤로 가기를 한 번 더 눌러야 나가는 일이 없게), 주소로 바로 들어왔으면 dup만 뺀다.
  const clearDuplicates = () => {
    if ((location.state as { dupPushed?: boolean } | null)?.dupPushed) navigate(-1)
    else setParams({}, { replace: true })
  }
  // 주소의 dup이 바뀌면 검색어를 맞춘다. 풀릴 때는 검색어가 아직 그 원문일 때만 비운다(직접 고친 검색어는 둔다).
  const prevDup = useRef<string | null>(null)
  useEffect(() => {
    const prev = prevDup.current
    prevDup.current = dup
    if (dup === prev) return
    if (dup !== null) applyQ(dup)
    else if (qRef.current === prev) applyQ('')
  }, [dup, applyQ])
  const pickViewSize = (v: ViewSize) => { setViewSize(v); storeViewSize(v); setRange([0, 0]); setViewPage(0); forgetPosition() }
  // 셀 우클릭 → 단어장에 추가(Task 12, Ruling 33): 패널을 열고 종류·맞추기·찾을 문자열을 채운다. seq는 매번
  // 늘려서, 같은 값을 다시 골라도 GlossaryPanel의 effect가 다시 반영하게 한다.
  const onAddToGlossary = useCallback((kind: GlossaryKind, text: string, whole: boolean) => {
    setGlossaryInitial((prev) => ({ kind, find: text, matchMode: whole ? 'WHOLE' : 'PARTIAL', seq: (prev?.seq ?? 0) + 1 }))
    setSidePanel('glossary')
  }, [])
  // 패널을 닫으면(닫기 버튼이든 TopBar 토글이든) 셀에서 채워진 initial을 지운다(Ruling 39) —
  // 안 지우면 다음에 토글로만 열었을 때도 지난번 셀 값이 남아 있다.
  const closeGlossary = () => { setSidePanel(null); setGlossaryInitial(undefined) }
  const toggleGlossary = () => { if (glossaryOpen) closeGlossary(); else { setGlossaryInitial(undefined); setSidePanel('glossary') } }
  const toggleActivity = () => { setGlossaryInitial(undefined); setSidePanel(activityOpen ? null : 'activity') }
  const onSelect = (c: Cell | null) => setSelection(c && rows[c.row] ? `${rows[c.row]!.rowNo}행 ${c.col === 'fix' ? 'Fix' : c.col === 'src' ? '원문' : c.col === 'existingKo' ? '기존' : c.col === 'note' ? '비고' : ''}`.trim() : '')

  // # 칸 색 범례: 지금 받아 둔 행을 마지막으로 고친 사람들(처음 나온 순)
  const editors = useMemo(() => {
    const seen = new Map<number, ReviewerRef>()
    for (const r of rows) if (r?.lastEditor && !seen.has(r.lastEditor.id)) seen.set(r.lastEditor.id, r.lastEditor)
    return [...seen.values()]
  }, [rows])

  return (
    <div className="flex h-screen flex-col bg-white">
      <TopBar fileName={project?.fileName ?? ''} gameName={project?.gameName ?? ''} gameId={project?.gameId ?? 0} projectId={id} reviewer={reviewer}
        presence={presence} status={status} exporting={exportXlsx.isPending} glossaryOpen={glossaryOpen} activityOpen={activityOpen}
        onRename={() => setRenaming(true)} onExport={() => exportXlsx.mutate()} onToggleGlossary={toggleGlossary} onToggleActivity={toggleActivity} />
      <div className="flex min-h-0 flex-1 overflow-hidden">
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
          <SheetBar sheets={sheets} active={sheet} filter={filter} q={q} hasReviewer={!!reviewer} onSheet={pickSheet} onFilter={pickFilter} onSearch={search} projectId={id} josaCount={summary.data?.josa} josaPending={summary.data?.josaPending}>
            <ViewSizePicker value={viewSize} onChange={pickViewSize} />
            {paged && !failed && <PageBar page={viewPage} size={viewSize} total={total} onPage={setViewPage} />}
            {sheet !== null && (
              <button type="button" onClick={() => setTagSpacingOpen(true)} title="태그 앞뒤에 붙은 낱말을 Kiwi로 보고 골라서 띄웁니다"
                className="press mb-1 whitespace-nowrap rounded border border-line bg-white px-2 py-0.5 text-xs text-body hover:bg-canvas">
                태그 띄어쓰기…
              </button>
            )}
            {sheet !== null && (
              <CommandRunButton run={commandRun} sheet={sheet} onOpen={setCommandFixSheet} />
            )}
            {filter === 'JOSA' && sheet !== null && (summary.data?.josa ?? 0) > 0 && (
              <button type="button" onClick={(e) => { const b = e.currentTarget.getBoundingClientRect(); setJosaDialog({ x: b.right, y: b.bottom + 4 }) }}
                className="press mb-1 rounded border border-line bg-white px-2 py-0.5 text-xs text-body hover:bg-canvas">
                모두 적용 ({(summary.data?.josa ?? 0).toLocaleString()})
              </button>
            )}
          </SheetBar>
          {dup !== null && (
            <div role="status" aria-label="같은 원문 모아 보기" className="flex items-center gap-2 border-b border-line bg-purpleSoft px-3 py-1 text-xs text-purple">
              <span>같은 원문 <span className="font-semibold">「{dup}」</span> 모아 보는 중 · {total.toLocaleString()}행</span>
              <button type="button" onClick={clearDuplicates} className="rounded border border-purple/30 bg-white px-1.5 py-0.5 text-purple hover:bg-canvas">
                <span aria-hidden="true">✕ </span>전체 보기
              </button>
              <span className="text-muted">브라우저 뒤로 가기로도 돌아갑니다</span>
            </div>
          )}
          {failed
            ? <div role="alert" className="flex flex-1 items-center justify-center text-[13px] text-orange">목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</div>
            // 쪽을 넘기거나 보기를 바꾸면 그리드를 새로 그려 스크롤·선택을 맨 위로 되돌린다
            : <WorkspaceGrid key={paged ? `${viewSize}:${viewPage}` : 'all'} rows={rows} ratio={RATIO} busy={busy} highlighted={highlighted} failedText={failedText}
                onSave={(row, text) => save.mutate({ row, text })} onMarks={(row, patch) => marks.mutate({ row, patch })}
                onBulkMarks={(key, on) => { if (sheet !== null) bulkMarks.mutate({ key, on }) }} viewCount={paged ? rows.length : total}
                onFilterDuplicates={filterDuplicates}
                onAddToGlossary={onAddToGlossary}
                onTranslate={(row, instructions) => translate.mutate({ row, instructions })} translating={translating}
                onShorten={(row, maxChars) => api.shortenRow(row.id, { maxChars, ratio: RATIO })}
                onJosaApply={(row) => josaApplyRow.mutate(row)} onJosaIgnore={(row) => josaIgnore.mutate(row)}
                onHistory={(row) => api.workspace.rowHistory(id, row.id)}
                onVisibleRange={onVisibleRange} onFocusCell={setEditing} onSelect={onSelect}
                onPosition={onPosition} restore={restore} onRestored={onRestored} />}
        </div>
        {/* 오른쪽 가장자리에서 들어오고 같은 길로 나간다. 닫힌 동안은 inert — 남아 있는 패널에 포커스·클릭이 가지 않게. */}
        <div inert={sidePanel === null} style={{ width: sidePanel === null ? 0 : PANEL_WIDTH }}
          className="flex flex-none overflow-hidden transition-[width] duration-200 ease-[cubic-bezier(0.2,0,0,1)] motion-reduce:transition-none">
          {shownPanel === 'glossary' && project && <GlossaryPanel key={panelSeq} gameId={project.gameId} projectId={id} onClose={closeGlossary} initial={glossaryInitial} />}
          {shownPanel === 'activity' && <ActivityPanel key={panelSeq} projectId={id} onClose={() => setSidePanel(null)} onJump={jumpTo} />}
        </div>
      </div>
      {josaDialog && sheet !== null && (
        <JosaApplyDialog x={josaDialog.x} y={josaDialog.y} projectId={id} sheet={sheet}
          onDone={() => { setJosaDialog(null); refreshRows(); qc.invalidateQueries({ queryKey: ['josa-preview', id] }) }}
          onClose={() => setJosaDialog(null)} />
      )}
      {tagSpacingOpen && sheet !== null && (
        <TagSpacingDialog projectId={id} sheet={sheet} onFixed={() => refreshRows()} onClose={() => setTagSpacingOpen(false)} />
      )}
      {commandFixSheet !== null && (
        <CommandFixDialog projectId={id} sheet={commandFixSheet} onFixed={() => refreshRows()} onClose={() => setCommandFixSheet(null)} />
      )}
      <CommandRunNotice run={commandRun} dialogOpen={commandFixSheet !== null} onOpen={setCommandFixSheet} />
      <StatusBar summary={summary.data} editors={editors} sheet={sheet} selection={selection} saveState={saveState} error={error} propagated={propagated} propagatedSeq={propagatedSeq}
        onDismissError={() => { setError(null); setSaveState('saved') }} />
      <ReviewerNameModal open={!reviewer || renaming} initialName={reviewer?.name} onClose={reviewer ? () => setRenaming(false) : undefined}
        onSubmit={async (name) => { await setName(name); setRenaming(false) }} />
    </div>
  )
}
