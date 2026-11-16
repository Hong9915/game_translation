import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ClipboardEvent, type CSSProperties, type FocusEvent, type KeyboardEvent, type MouseEvent, type ReactNode } from 'react'
import type { GlossaryKind, MarkKey, MarksPatch, RowHistoryEntry, RowShortenResult, WorkspaceRow } from '../../api/types'
import { COLS, distributePaste, move, nextFixRow, type ArrowKey, type Cell, type Col } from '../../lib/gridNav'
import { DEFAULT_ROW_HEIGHT, indexAt, rowOffsets, UNHIDEABLE, useGridLayout, type WidthCol } from '../../lib/gridLayout'
import { columnClipboard, inRange, rangeBounds, type CellRange, type RangeCol } from '../../lib/gridRange'
import { lengthFlagged } from '../../lib/lengthCheck'
import { relativeTime } from '../../lib/relativeTime'
import { Anchored } from '../ui/Anchored'
import { BulkMarkPopover } from './BulkMarkPopover'
import { JosaPopover } from './JosaPopover'
import { RowHistoryPopover } from './RowHistoryPopover'
import { ShortenPopover } from './ShortenPopover'

export const ROW_HEIGHT = DEFAULT_ROW_HEIGHT
const OVERSCAN = 50
const FALLBACK_VIEW_ROWS = 40   // jsdom처럼 높이를 모를 때
/** 범위 메뉴(다시 번역·다듬기)로 한 번에 보낼 수 있는 행 수. */
const RANGE_ACTION_MAX = 500

export interface WorkspaceGridProps {
  rows: (WorkspaceRow | undefined)[]
  /** 게임 전체 리뷰 화면이면 ID 뒤에 "파일 / 시트" 열을 보인다(설계 2026-10-12 §2). 키보드로는 건너뛰는 표시 전용 열. */
  placeOf?: (r: WorkspaceRow) => string
  /** 최종 리뷰 시트면 최종 수정 열을 보이고, Fix 칸을 달라짐 노랑 대신 최종 수정 연두로 칠한다. */
  finalReview?: boolean
  ratio: number
  busy: Set<number>
  highlighted: Set<number>
  /** 저장 실패로 아직 반영 안 된 입력(세그먼트 id → { 입력한 텍스트, 그때의 r.fix }). r.fix 대신 text를 보여주고
   * 빨간 테두리를 준다(설계 §5). base는 그 행이 다른 곳(SSE 등)에서 바뀌었는지 부모가 판단하는 데 쓰는 값이라
   * 그리드는 base를 보지 않는다 — 항목이 남아 있으면 그냥 실패 표시를 한다. */
  failedText: Map<number, { text: string; base: string }>
  onSave: (row: WorkspaceRow, text: string) => void
  /** 여러 줄 붙여넣기 — 바뀌는 행만 모아 한 번에 넘긴다(빈 줄·지금과 같은 글은 뺀다). */
  onSaveMany: (items: { row: WorkspaceRow; text: string }[]) => void
  /** 편집 중이 아닐 때 Ctrl/⌘+Z — 서버 되돌리기. */
  onUndo: () => void
  /** 편집 중이 아닐 때 Ctrl/⌘+Y 또는 Ctrl/⌘+Shift+Z — 서버 다시 하기. */
  onRedo: () => void
  /** Fix 머리글 우클릭 "Fix 열 전체 복사". 이 보기의 행을 모으는 건 부모가 한다. */
  onCopyAllFix: () => void
  /** Fix·원문 범위 우클릭 메뉴 — 원문은 DeepL·GPT 번역, Fix는 GPT로 다듬기. */
  onRangeAction: (action: 'deepl' | 'gpt' | 'polish', rows: WorkspaceRow[]) => void
  /** "아직 안 들어온 행이 있어 복사하지 않았습니다" 같은 짧은 알림. 부모가 상태 표시에 띄운다. */
  onNotice: (msg: string) => void
  /** 1차·2차·확인 필요·확정 체크와 비고 변경. 준 칸만 바꾼다(서로 독립). */
  onMarks: (row: WorkspaceRow, patch: MarksPatch) => void
  /** 체크 머리글 더블클릭 → 일괄 체크 창에서 고르면 부른다. 대상(지금 보기)은 부모가 정한다. */
  onBulkMarks: (key: MarkKey, on: boolean) => void
  /** 일괄 체크가 적용될 행 수(쪽 보기면 그 쪽, 전체 보기면 조건에 걸린 전부). 창에 보인다. */
  viewCount: number
  /** 원문 셀의 ×N 칩을 누르면 그 원문으로 검색어(중복 필터)를 건다. */
  /** ×N 칩 메뉴에서 고른 모아 보기. exact = 원문이 같은 행, contains = 원문에 이 글이 든 행. */
  onFilterDuplicates: (src: string, mode: 'exact' | 'contains') => void
  /** 원문·Fix 셀 우클릭 메뉴에서 "단어장에 추가"를 고르면 부른다. whole=true면 셀 전체 값, false면 그 안의 선택 영역. */
  onAddToGlossary: (kind: GlossaryKind, text: string, whole: boolean) => void
  /** 원문 셀 우클릭 메뉴의 "DeepL 번역"을 누르면 부른다. 결과는 그 행의 Fix를 덮어쓴다. */
  onTranslate: (row: WorkspaceRow) => void
  /** 기존 한국어·Fix 셀 우클릭 "짧게 줄이기"에서 GPT 후보를 받는다(저장 안 함). 고른 후보는 onSave로 저장한다. */
  onShorten: (row: WorkspaceRow, maxChars?: number) => Promise<RowShortenResult>
  /** Fix 셀 `조사` 배지 창의 [적용] — 그 행의 제안을 종류 상관없이 모두 고친다. */
  onJosaApply: (row: WorkspaceRow) => void
  /** 같은 창의 [무시] — 번역이 다시 바뀌기 전까지 이 행 제안을 숨긴다. */
  onJosaIgnore: (row: WorkspaceRow) => void
  /** 우클릭 "변경 내역 보기" — 그 행의 판을 최신부터 받는다. */
  onHistory: (row: WorkspaceRow) => Promise<RowHistoryEntry[]>
  /** 번역 응답을 기다리는 행. Fix 칸에 "번역 중…"을 보이고 편집을 막는다. */
  translating: Set<number>
  onVisibleRange: (start: number, end: number) => void
  onFocusCell?: (id: number | null) => void
  onSelect?: (cell: Cell | null) => void
  /** 고른 칸이나 스크롤이 바뀔 때마다 알린다. 부모가 검색에 들어가기 전 자리를 기억하는 데 쓴다. */
  onPosition?: (pos: GridPosition) => void
  /** 이 자리로 되돌린다(검색·모아 보기를 풀 때). 행이 그만큼 들어와야 적용하고, 적용하면 onRestored를 부른다. */
  restore?: GridPosition | null
  onRestored?: () => void
}

export interface GridPosition { cell: Cell | null; scrollTop: number }

const HEADERS: Record<Col, string> = {
  idx: '#', id: 'ID', src: '간체 원문', en: '영어', existingKo: '기존 한국어', fix: '수정 한국어 (Fix)',
  review1: 'A', review2: 'B', review3: 'C', needsCheck: '확인 필요', confirmed: '확정', finalMark: '최종 수정', note: '비고',
}
/**
 * # 칸을 마지막으로 고친 사람의 색으로(2026-10-08): 왼쪽 띠와 옅은 바탕. 바탕은 칸 배경색 위에 그라디언트로 얹는다 —
 * 가로로 스크롤하면 고정된 # 칸 밑으로 다른 칸이 지나가므로 바탕이 비치면 안 된다.
 */
function editorTint(color: string | undefined): CSSProperties | undefined {
  if (!color) return undefined
  const tint = /^#[0-9a-f]{6}$/i.test(color) ? `${color}33` : 'transparent'
  return { boxShadow: `inset 3px 0 0 ${color}`, backgroundImage: `linear-gradient(${tint}, ${tint})` }
}

const MARKS: MarkKey[] = ['review1', 'review2', 'review3', 'needsCheck', 'confirmed', 'finalMark']
const isMark = (c: Col): c is MarkKey => (MARKS as string[]).includes(c)
/** 글을 편집하는 칸. Fix는 저장(onSave), 비고는 onMarks로 간다. */
type TextCol = 'fix' | 'note'
const isText = (c: Col): c is TextCol => c === 'fix' || c === 'note'
const ARROWS: ArrowKey[] = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']
const isRangeCol = (c: Col): c is RangeCol => c === 'fix' || c === 'src' || c === 'existingKo'

/** 엑셀식 검수 표. 조회·저장은 부모(WorkspacePage)가 하고, 여기서는 선택·편집·가상 스크롤만 다룬다. */
export function WorkspaceGrid({ rows, placeOf, finalReview = false, ratio, busy, highlighted, failedText, onSave, onSaveMany, onUndo, onRedo, onCopyAllFix, onRangeAction, onNotice, onMarks, onBulkMarks, viewCount, onFilterDuplicates, onAddToGlossary, onTranslate, onShorten, onJosaApply, onJosaIgnore, onHistory, translating, onVisibleRange, onFocusCell, onSelect, onPosition, restore, onRestored }: WorkspaceGridProps) {
  const box = useRef<HTMLDivElement>(null)
  const [scrollTop, setScrollTop] = useState(0)
  const [viewHeight, setViewHeight] = useState(0)
  const [cell, setCellState] = useState<Cell | null>(null)
  // where: 편집 상자가 어디 떠 있나 — 칸 안('cell') 또는 표 위 입력줄('bar'). 같은 편집을 두 곳이 나눠 쓴다.
  // col: 편집 중인 칸(Fix 또는 비고). id: 편집 중인 행의 세그먼트 id — 줄 번호로 기억하면 편집 중에 목록이 다시 읽혀
  // (체크 필터·일괄 체크·refresh) 행이 밀렸을 때 다른 행에 저장된다. 줄 번호는 렌더마다 id로 다시 찾는다.
  const [editing, setEditing] = useState<{ id: number; col: TextCol; text: string; where: 'cell' | 'bar' } | null>(null)
  const barRef = useRef<HTMLTextAreaElement>(null)
  // 원문·Fix 셀 우클릭 메뉴. 좌표는 우클릭 시점의 화면 좌표(fixed 위치용).
  // row는 원문 셀에서 연 메뉴에만 있다 — "DeepL 번역" 항목이 그 행을 번역한다.
  // shorten은 기존 한국어·Fix 셀에서 연 메뉴에만 있다 — "짧게 줄이기" 항목이 그 행의 한국어를 줄인다.
  // history는 # 칸·원문·기존 한국어·Fix 칸 메뉴에 있다 — "변경 내역 보기". # 칸 메뉴는 text가 비어 단어장 항목이 없다.
  // hideCol은 열 머리글 메뉴에만 있다 — "열 숨기기".
  // copyAllFix는 Fix 머리글 메뉴에만 있다 — "Fix 열 전체 복사". range는 범위 안을 우클릭했을 때 — 그 범위의 행(안 들어온 행은 undefined).
  const [menu, setMenu] = useState<{ x: number; y: number; kind: GlossaryKind; text: string; whole: boolean; row?: WorkspaceRow; shorten?: WorkspaceRow; history?: WorkspaceRow; hideCol?: WidthCol; dup?: string; copyAllFix?: boolean; range?: { col: RangeCol; rows: (WorkspaceRow | undefined)[] } } | null>(null)
  // 우클릭 "변경 내역 보기" 창. 메뉴와 같은 자리에 뜬다.
  const [historyOf, setHistoryOf] = useState<{ x: number; y: number; row: WorkspaceRow } | null>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  // 체크 머리글 더블클릭 일괄 체크 창. 머리글 바로 아래에 뜬다.
  const [bulk, setBulk] = useState<{ x: number; y: number; key: MarkKey } | null>(null)
  // 용어 불일치 점에 마우스를 올리면 바로 뜨는 목록(브라우저 title은 1초쯤 늦고 8px 점이라 맞추기 어렵다).
  const [termTip, setTermTip] = useState<{ x: number; y: number; items: string[] } | null>(null)
  const [josaTip, setJosaTip] = useState<{ x: number; y: number; row: WorkspaceRow } | null>(null)
  const closeBulk = useCallback(() => { setBulk(null); box.current?.focus() }, [])
  // "짧게 줄이기" 후보 창. 메뉴와 같은 자리에 뜬다.
  const [shortening, setShortening] = useState<{ x: number; y: number; row: WorkspaceRow } | null>(null)
  // Enter/Tab/Escape가 편집을 끝내며 grid로 focus를 옮기면, 아직 안 지워진 textarea가 그 자리에서
  // blur된다. setEditing(null)은 다음 렌더까지 반영이 안 되므로 blur 핸들러가 옛 editing을 보고
  // 또 commit하는 걸 막는 플래그.
  const finishingRef = useRef(false)
  const total = rows.length
  // Fix·원문 열에서 여러 행을 고른 범위(엑셀처럼). 고른 칸(cell)은 늘 범위의 head 칸이다.
  // 줄 번호와 함께 그 줄의 세그먼트 id도 기억한다 — 편집처럼, 필터·검색·실시간 반영으로 목록이 바뀌면 줄 번호가
  // 다른 행을 가리키게 된다. 렌더마다 두 끝의 id를 다시 맞춰 보고 다르면(빠졌거나 밀렸으면) 범위를 푼다.
  // 아직 안 들어온 행 끝은 id가 없다 — 그 행이 들어오면 맞지 않으니 범위가 풀린다.
  const [rangeState, setRangeState] = useState<{ r: CellRange; anchorId?: number; headId?: number } | null>(null)
  const rangeLive = (st: typeof rangeState): st is NonNullable<typeof rangeState> =>
    !!st && st.r.anchor < rows.length && st.r.head < rows.length && rows[st.r.anchor]?.id === st.anchorId && rows[st.r.head]?.id === st.headId
  const range = rangeLive(rangeState) ? rangeState.r : null
  const rangeGone = rangeState !== null && range === null
  useEffect(() => { if (rangeGone) setRangeState(null) }, [rangeGone])
  const setRange = (r: CellRange | null) => setRangeState(r ? { r, anchorId: rows[r.anchor]?.id, headId: rows[r.head]?.id } : null)
  // 왼쪽 버튼을 누른 Fix·원문 칸. 누른 채 다른 행의 같은 열 칸에 들어가면 범위가 된다. 놓으면 지운다.
  const pressRef = useRef<{ row: number; col: RangeCol } | null>(null)
  useEffect(() => {
    const onUp = () => { pressRef.current = null }
    window.addEventListener('mouseup', onUp)
    return () => window.removeEventListener('mouseup', onUp)
  }, [])

  // 열 숨기기·행 높이·열 너비(2026-10-12): 화면에만 걸리고 이 브라우저에 기억한다. 행 높이는 모든 행이 같이 바뀐다.
  const layout = useGridLayout()
  const { hiddenCols, rowHeight, widths } = layout
  const offsets = useMemo(() => rowOffsets(new Array<number>(rows.length).fill(rowHeight)), [rows.length, rowHeight])
  // 최종 수정 열은 최종 리뷰 시트에서만(설계 2026-10-17)
  const show = (c: WidthCol) => !hiddenCols.has(c) && (c !== 'finalMark' || finalReview)
  const shownCols = COLS.filter(show)
  const colOrder: WidthCol[] = (placeOf ? COLS.flatMap((c): WidthCol[] => (c === 'id' ? [c, 'place'] : [c])) : [...COLS]).filter(show)
  const tableWidth = colOrder.reduce((n, c) => n + widths[c], 0)

  const position = useRef<GridPosition>({ cell: null, scrollTop: 0 })
  const setCell = useCallback((c: Cell | null) => {
    setCellState(c); onSelect?.(c)
    position.current = { ...position.current, cell: c }; onPosition?.(position.current)
  }, [onSelect, onPosition])
  const onBoxScroll = (top: number) => {
    setScrollTop(top)
    position.current = { ...position.current, scrollTop: top }; onPosition?.(position.current)
  }

  // 검색·모아 보기를 풀면 들어가기 전 자리로 돌아간다. 전체 목록이 아직 안 들어와 그만큼 스크롤할 수 없으면
  // 행이 더 들어온 다음 렌더에서 다시 해 본다(높이를 모르는 jsdom에서는 스크롤은 건너뛰고 칸만 고른다).
  useLayoutEffect(() => {
    if (!restore || total === 0) return
    const el = box.current
    if (el && el.clientHeight > 0) {
      el.scrollTop = restore.scrollTop
      if (Math.abs(el.scrollTop - restore.scrollTop) > 1) return
      setScrollTop(el.scrollTop)
    }
    setCell(restore.cell && restore.cell.row < total ? restore.cell : null)
    onRestored?.()
  }, [restore, total, setCell, onRestored])

  useLayoutEffect(() => { setViewHeight(box.current?.clientHeight ?? 0) }, [])
  // 아는 행 아래(목록이 아직 비었거나 짧을 때)도 같은 행 높이로 이어 센다 — 스크롤 위치가 가리키는 쪽을 그대로 받으러 가게.
  const rowAt = (y: number) => (y >= offsets[total] ? total + Math.floor((y - offsets[total]) / rowHeight) : indexAt(offsets, y))
  const firstVisible = rowAt(scrollTop)
  const lastVisible = viewHeight > 0 ? rowAt(scrollTop + viewHeight) : firstVisible + FALLBACK_VIEW_ROWS
  const start = Math.max(0, firstVisible - OVERSCAN)
  const end = Math.min(total, lastVisible + 1 + OVERSCAN)   // exclusive
  useEffect(() => { onVisibleRange(start, end) }, [start, end, onVisibleRange])

  // 선택한 행이 보이도록 스크롤
  const scrollIntoView = (r: number) => {
    const el = box.current
    if (!el || el.clientHeight === 0) return
    const top = offsets[r] ?? 0, bottom = offsets[r + 1] ?? top + ROW_HEIGHT
    if (top < el.scrollTop) el.scrollTop = top
    else if (bottom > el.scrollTop + el.clientHeight - ROW_HEIGHT) el.scrollTop = bottom - el.clientHeight + ROW_HEIGHT
  }

  const startEdit = (r: number, initial?: string, where: 'cell' | 'bar' = 'cell', col: TextCol = 'fix') => {
    const row = rows[r]
    if (!row) return
    setRange(null)
    if (col === 'fix') {
      if (busy.has(row.id) || translating.has(row.id)) return
      // 저장 실패로 남은 입력이 있으면 거기서부터 다시 편집한다(설계 §5: 값을 화면에 남겨두고 다시 시도).
      setEditing({ id: row.id, col, text: initial ?? failedText.get(row.id)?.text ?? row.fix ?? '', where })
    } else {
      setEditing({ id: row.id, col, text: initial ?? row.note ?? '', where })
    }
    onFocusCell?.(row.id)
  }
  const commit = (moveTo: 'down' | 'next' | 'stay') => {
    if (!editing) return
    const idx = rows.findIndex((r) => r?.id === editing.id)
    const row = idx >= 0 ? rows[idx] : undefined
    const text = editing.text
    setEditing(null)
    onFocusCell?.(null)
    if (row && editing.col === 'fix' && text.trim() !== (row.fix ?? '').trim() && text.trim() !== '') onSave(row, text)
    // 비고는 비워서 지우는 것도 저장이다
    if (row && editing.col === 'note' && text.trim() !== (row.note ?? '').trim()) onMarks(row, { note: text })
    const next = moveTo === 'stay' || idx < 0 ? null : nextFixRow(idx, total, 1)
    if (next !== null) { setCell({ row: next, col: editing.col }); scrollIntoView(next) }
  }
  const toggleMark = (row: WorkspaceRow, key: MarkKey) => onMarks(row, { [key]: !row[key] } as MarksPatch)
  // 칸은 마우스를 누르는 순간 고른다(2026-10-12) — 표 전체가 select-none이고 고른 칸만 글자 선택이 열리므로,
  // 드래그를 시작한 칸이 곧 글자를 고를 수 있는 유일한 칸이 되어 옆 칸·아래 행으로 번지지 않는다. 우클릭은 고르지 않는다.
  const pick = (i: number, c: Col) => (e: MouseEvent<HTMLTableCellElement>) => {
    if (e.button !== 0) return
    // 위 입력줄에서 고치던 중이면 먼저 blur해 저장한다 — 여기서 칸이 바뀌면 입력줄이 focus를 잃기 전에 사라져
    // blur 저장이 안 불리고 editing이 남아 키보드가 먹통이 된다(글이 아닌 칸을 누를 때).
    if (editing?.where === 'bar') barRef.current?.blur()
    // Shift+클릭(같은 Fix·원문 열): 지금 칸(범위가 있으면 그 시작)부터 누른 칸까지 범위로
    if (e.shiftKey && isRangeCol(c) && cell?.col === c && !editing) {
      e.preventDefault()
      clearTextSelection()
      setRange({ col: c, anchor: range?.col === c ? range.anchor : cell.row, head: i })
      setCell({ row: i, col: c })
      return
    }
    setRange(null)
    pressRef.current = isRangeCol(c) ? { row: i, col: c } : null
    setCell({ row: i, col: c })
  }
  // 누른 채 다른 행의 같은 열 칸에 들어가면 범위를 만든다. 같은 칸 안에서 움직이면 글자 선택 그대로 둔다.
  const dragInto = (i: number, c: RangeCol) => (e: MouseEvent<HTMLTableCellElement>) => {
    const p = pressRef.current
    // 편집 상자 안에서 끌다 밖으로 나가도 범위로 바꾸지 않는다
    if (!p || p.col !== c || (e.buttons & 1) === 0 || editing) return
    if (i === p.row && !range) return
    clearTextSelection()
    setRange({ col: c, anchor: p.row, head: i })
    setCell({ row: i, col: c })
  }
  // 범위 안 우클릭이면 범위 메뉴(맨 위 복사, 원문은 번역·Fix는 다듬기)를 연다. 처리했으면 true.
  const openRangeMenu = (e: MouseEvent<HTMLTableCellElement>, i: number, c: RangeCol) => {
    if (!range || !inRange(range, i, c)) return false
    e.preventDefault()
    const [lo, hi] = rangeBounds(range)
    setMenu({ x: e.clientX, y: e.clientY, kind: c === 'src' ? 'SRC' : 'KO', text: '', whole: true, range: { col: c, rows: rows.slice(lo, hi + 1) } })
    return true
  }
  // 고른 칸의 값 글자(배지·칩 제외). 가상 스크롤로 그 행이 DOM에 없으면 null.
  const cellTextEl = (c: Cell) => box.current?.querySelector<HTMLElement>(`tr[data-row="${c.row}"] [data-cell-text="${c.col}"]`) ?? null
  // 칸을 키보드로 옮기면 앞 칸에서 긁어 둔 글자 선택을 지운다 — 남아 있으면 Ctrl+C가 옛 칸 글자를 복사한다.
  const clearTextSelection = () => window.getSelection()?.removeAllRanges()
  // 범위 칸의 글: 원문, 기존 한국어, 또는 Fix(저장 실패 입력이 있으면 그 값 — 한 칸 복사와 같게)
  const rangeText = (r: WorkspaceRow, c: RangeCol) => (c === 'src' ? r.src : c === 'existingKo' ? r.existingKo : failedText.get(r.id)?.text ?? r.fix)
  // 범위 행의 그 열을 엑셀 한 열처럼 복사한다(Ctrl+C·우클릭 "복사"). 안 들어온 행이 끼어 있으면 복사하지 않는다.
  const copyRange = (c: RangeCol, rs: (WorkspaceRow | undefined)[]) => {
    if (rs.some((r) => r === undefined)) { onNotice('아직 안 들어온 행이 있어 복사하지 않았습니다'); return }
    copyText(columnClipboard(rs.map((r) => rangeText(r!, c))))
  }
  const copyText = (t: string) => { navigator.clipboard?.writeText(t).catch(() => { /* 클립보드 권한이 없으면 복사만 못 한다 */ }) }
  // 편집하던 행이 목록에서 빠졌으면(필터 결과가 바뀜) 편집을 버린다 — 다른 행에 저장하지 않는다.
  const editingGone = editing !== null && !rows.some((r) => r?.id === editing.id)
  useEffect(() => { if (editingGone) { setEditing(null); onFocusCell?.(null) } }, [editingGone, onFocusCell])
  const cancel = () => { finishingRef.current = true; setEditing(null); onFocusCell?.(null); box.current?.focus() }

  // 메뉴가 떠 있는 동안 바깥 클릭·Esc·스크롤로 닫는다(Ruling 34). 메뉴 안쪽 클릭은 안 닫는다 — 항목 자체가
  // 고르고 나서 스스로 닫는다.
  useEffect(() => {
    if (!menu) return
    const onDown = (e: globalThis.MouseEvent) => { if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenu(null) }
    // Esc는 window의 캡처 단계에서 가로챈다. React는 root 컨테이너에 붙인 버블 단계 리스너로 합성 이벤트를
    // 처리하므로(예: Fix 편집 textarea의 onKeyDown), document의 버블 리스너로는 그보다 늦게 도착해
    // stopPropagation을 걸어도 이미 늦다. 캡처 단계에서 먼저 잡아 stopPropagation·preventDefault로
    // 이 이벤트가 target(다른 칸의 편집 중인 textarea)에 아예 안 닿게 막아, 메뉴만 닫고 그 편집은 그대로 둔다.
    const onEsc = (e: globalThis.KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      setMenu(null)
    }
    const onScroll = () => setMenu(null)
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc, true)
    document.addEventListener('scroll', onScroll, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onEsc, true)
      document.removeEventListener('scroll', onScroll, true)
    }
  }, [menu])

  // 우클릭 시점의 선택 영역(window.getSelection())을 읽는다 — 메뉴 항목을 누를 때 읽으면 클릭이 선택을
  // 지운 뒤라 이미 늦다. 선택이 그 셀 안에 있을 때만 쓰고, 아니면 셀 전체 값을 쓴다.
  const onCellContextMenu = (e: MouseEvent<HTMLTableCellElement>, kind: GlossaryKind, wholeValue: string, extra: { row?: WorkspaceRow; shorten?: WorkspaceRow; history?: WorkspaceRow } = {}) => {
    e.preventDefault()
    const cellEl = e.currentTarget
    const sel = window.getSelection()
    const selText = sel?.toString() ?? ''
    const useSelection = !!(selText.trim() && sel?.anchorNode && cellEl.contains(sel.anchorNode))
    const text = (useSelection ? selText : wholeValue).trim()
    if (!text && !extra.history) { setMenu(null); return }
    setMenu({ x: e.clientX, y: e.clientY, kind, text, whole: !useSelection, ...extra })
  }

  const openShorten = () => {
    if (!menu?.shorten) return
    setShortening({ x: menu.x, y: menu.y, row: menu.shorten })
    setMenu(null)
  }
  const closeShorten = () => { setShortening(null); box.current?.focus() }
  const pickShortened = (ko: string) => {
    if (!shortening) return
    const row = shortening.row
    if (ko.trim() !== (row.fix ?? '').trim()) onSave(row, ko)
    closeShorten()
  }

  const hideCol = () => {
    if (!menu?.hideCol) return
    const c = menu.hideCol
    layout.hideCol(c)
    if (range?.col === c) setRange(null)
    // 고른 칸이 그 열에 있었으면 Fix로 옮긴다
    if (cell && cell.col === c) setCell({ ...cell, col: 'fix' })
    setMenu(null)
  }

  // # 칸 아래 경계를 끌면 그 행 높이, 머리글 오른쪽 경계를 끌면 그 열 너비를 바꾼다. 놓을 때까지 window에서 마우스를 따라간다.
  const dragFrom = (e: MouseEvent<HTMLDivElement>, axis: 'x' | 'y', apply: (delta: number) => void) => {
    e.preventDefault(); e.stopPropagation()
    const from = axis === 'x' ? e.clientX : e.clientY
    const onMove = (ev: globalThis.MouseEvent) => apply((axis === 'x' ? ev.clientX : ev.clientY) - from)
    const onUp = (ev: globalThis.MouseEvent) => {
      onMove(ev)
      window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove); window.addEventListener('mouseup', onUp)
  }
  const startRowResize = (e: MouseEvent<HTMLDivElement>) => {
    const h0 = rowHeight
    dragFrom(e, 'y', (d) => layout.setRowHeight(h0 + d))
  }
  const startBarResize = (e: MouseEvent<HTMLDivElement>) => {
    const h0 = layout.barHeight
    dragFrom(e, 'y', (d) => layout.setBarHeight(h0 + d))
  }
  const startColResize = (e: MouseEvent<HTMLDivElement>, c: WidthCol) => {
    const w0 = widths[c]
    dragFrom(e, 'x', (d) => layout.setWidth(c, w0 + d))
  }

  const openHistory = () => {
    if (!menu?.history) return
    setHistoryOf({ x: menu.x, y: menu.y, row: menu.history })
    setMenu(null)
  }
  const closeHistory = () => { setHistoryOf(null); box.current?.focus() }
  // 창을 연 뒤 저장·실시간 반영으로 바뀐 행을 쓴다(되돌리기 단추가 지금 본문과 비교한다)
  const historyRow = historyOf ? (rows.find((r) => r?.id === historyOf.row.id) ?? historyOf.row) : null

  // 원문 우클릭 "DeepL 번역": 지시 창 없이 바로 번역한다(DeepL은 지시를 따르지 못한다 — 문장을 고치려면 "명령으로 고치기")
  const translateRow = () => {
    if (!menu?.row) return
    const row = menu.row
    setMenu(null)
    box.current?.focus()
    onTranslate(row)
  }
  const runRangeAction = (action: 'deepl' | 'gpt' | 'polish') => {
    const rs = menu?.range?.rows
    setMenu(null)
    box.current?.focus()
    if (!rs || rs.some((r) => r === undefined)) return
    onRangeAction(action, rs as WorkspaceRow[])
  }


  // 엑셀 수식 입력줄처럼 선택한 칸의 전체 글을 표 위에 보인다. 칸은 한 줄이라 긴 글이 잘리기 때문이다.
  // Fix를 편집하는 중이면 입력 중인 글을 그대로 따라 보인다.
  const selRow = cell ? rows[cell.row] : undefined
  const barText = !cell || !selRow ? null
    : cell.col === 'fix' ? (editing?.id === selRow.id && editing.col === 'fix' ? editing.text : failedText.get(selRow.id)?.text ?? selRow.fix ?? '')
    : cell.col === 'note' ? (editing?.id === selRow.id && editing.col === 'note' ? editing.text : selRow.note ?? '')
    : cell.col === 'src' ? selRow.src
    : cell.col === 'en' ? selRow.en ?? ''
    : cell.col === 'existingKo' ? selRow.existingKo ?? ''
    : cell.col === 'id' ? selRow.keyText ?? selRow.indexLabel
    : null

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (editing) return   // textarea가 처리한다(편집 중 Ctrl+Z는 글 상자 자체 되돌리기)
    // 단축키는 e.code로 본다 — 한글 자판이면 e.key가 'ㅁ'·'ㅊ'로 온다
    const mod = (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey
    // 서버 되돌리기·다시 하기
    if (mod && e.code === 'KeyZ') { e.preventDefault(); onUndo(); return }
    if ((e.metaKey || e.ctrlKey) && !e.altKey && (e.code === 'KeyY' || (e.shiftKey && e.code === 'KeyZ'))) { e.preventDefault(); onRedo(); return }
    if (mod && e.code === 'KeyA') {
      // 화면 전체 대신 고른 칸의 값 글자만 고른다. 값 글자가 없는 칸(#·체크·번역 중)이면 아무것도 안 고른다.
      e.preventDefault()
      const sel = window.getSelection()
      sel?.removeAllRanges()
      const el = cell && cellTextEl(cell)
      if (sel && el) { const range = document.createRange(); range.selectNodeContents(el); sel.addRange(range) }
      return
    }
    if (mod && e.code === 'KeyC' && range) {
      e.preventDefault()
      const [lo, hi] = rangeBounds(range)
      copyRange(range.col, rows.slice(lo, hi + 1))
      return
    }
    if (mod && e.code === 'KeyC') {
      // 고른 칸 안에 긁어 둔 글자가 있으면 그 부분만(브라우저 기본 복사). 없으면 고른 칸 전체 값 — 칸이 좁아 잘려 보여도 전부.
      const sel = window.getSelection()
      const td = cell ? cellTextEl(cell)?.closest('td') : null
      if (sel && sel.toString() !== '' && td && sel.anchorNode && td.contains(sel.anchorNode)) return
      if (!barText) return
      e.preventDefault()
      navigator.clipboard?.writeText(barText).catch(() => { /* 클립보드 권한이 없으면 복사만 못 한다 */ })
      return
    }
    // 아직 아무 칸도 안 골랐으면 첫 키는 1행 Fix를 고르는 데 쓴다(엑셀의 A1처럼 늘 같은 자리에서 시작)
    if (!cell) { if (total > 0) setCell({ row: 0, col: 'fix' }); return }
    const row = rows[cell.row]
    // Shift+↑/↓(Fix·원문·기존 한국어 칸): 범위 끝(head)을 옮긴다. 범위가 없으면 지금 행에서 시작한다.
    if (e.shiftKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown') && isRangeCol(cell.col)) {
      e.preventDefault()
      const c = cell.col
      const anchor = range?.col === c ? range.anchor : cell.row
      const head = Math.max(0, Math.min(total - 1, (range?.col === c ? range.head : cell.row) + (e.key === 'ArrowDown' ? 1 : -1)))
      clearTextSelection(); setRange({ col: c, anchor, head }); setCell({ row: head, col: c }); scrollIntoView(head); return
    }
    if (ARROWS.includes(e.key as ArrowKey)) {
      e.preventDefault()
      setRange(null)
      // 좌우는 숨긴 열을 건너뛴다. 끝까지 숨긴 열뿐이면 그 자리에 있는다.
      let c = move(cell, e.key as ArrowKey, total)
      while (!show(c.col)) { const n = move(c, e.key as ArrowKey, total); if (n.col === c.col) { c = cell; break } c = n }
      clearTextSelection(); setCell(c); scrollIntoView(c.row); return
    }
    if (e.key === 'Tab') {
      e.preventDefault()
      setRange(null)
      const next = nextFixRow(cell.row, total, e.shiftKey ? -1 : 1)
      if (next !== null) { clearTextSelection(); setCell({ row: next, col: 'fix' }); scrollIntoView(next) }
      return
    }
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); if (row) toggleMark(row, 'review1'); return }
    if (isMark(cell.col)) { if (e.key === ' ' && row) { e.preventDefault(); toggleMark(row, cell.col) } return }
    if (!isText(cell.col)) return
    if (e.key === 'Enter' || e.key === 'F2') { e.preventDefault(); startEdit(cell.row, undefined, 'cell', cell.col); return }
    if (e.key.length === 1 && !e.metaKey && !e.ctrlKey && !e.altKey) { e.preventDefault(); startEdit(cell.row, e.key, 'cell', cell.col) }
  }

  const onPaste = (e: ClipboardEvent<HTMLDivElement>) => {
    if (editing || !cell || cell.col !== 'fix') return
    e.preventDefault()
    // 범위가 있으면 엑셀처럼 범위 맨 위 행부터 붙이고, 붙인 뒤 범위를 푼다
    const from = range ? rangeBounds(range)[0] : cell.row
    if (range) setRange(null)
    const items: { row: WorkspaceRow; text: string }[] = []
    for (const { index, text } of distributePaste(e.clipboardData.getData('text'), from, rows)) {
      const row = rows[index]
      if (row && text.trim() !== '' && text.trim() !== (row.fix ?? '').trim()) items.push({ row, text })
    }
    if (items.length > 0) onSaveMany(items)
  }

  // 입력줄을 누르면 거기서 편집을 시작한다. 칸 안에서 편집하던 중이면 저장하지 않고 입력줄로 옮겨 이어 쓴다.
  const onBarFocus = () => {
    if (!cell || !isText(cell.col)) return
    if (editing && editing.id === rows[cell.row]?.id && editing.col === cell.col) { if (editing.where !== 'bar') setEditing({ ...editing, where: 'bar' }); return }
    startEdit(cell.row, undefined, 'bar', cell.col)
  }
  const onEditorBlur = (e: FocusEvent<HTMLTextAreaElement>) => {
    if (finishingRef.current) { finishingRef.current = false; return }
    if (e.relatedTarget === barRef.current) return   // 입력줄로 옮겨가는 중 — onBarFocus가 이어받는다
    commit('stay')
  }

  const onEditorKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); commit('down'); finishingRef.current = true; box.current?.focus() }
    else if (e.key === 'Tab') { e.preventDefault(); commit('next'); finishingRef.current = true; box.current?.focus() }
    else if (e.key === 'Escape') { e.preventDefault(); cancel() }
  }

  const visible: number[] = []
  for (let i = start; i < end; i++) visible.push(i)

  // 열 머리글 우클릭: "열 숨기기". # 칸과 Fix는 숨기지 않는다(메뉴를 열지 않는다).
  const openColMenu = (e: MouseEvent<HTMLTableCellElement>, c: WidthCol) => {
    e.preventDefault()
    // Fix는 숨기지 않지만 "Fix 열 전체 복사" 메뉴를 연다
    if (c === 'fix') { setMenu({ x: e.clientX, y: e.clientY, kind: 'KO', text: '', whole: true, copyAllFix: true }); return }
    if (UNHIDEABLE.has(c)) { setMenu(null); return }
    setMenu({ x: e.clientX, y: e.clientY, kind: 'KO', text: '', whole: true, hideCol: c })
  }

  const widthHandle = (c: WidthCol, label: string) => (
    <div role="separator" aria-orientation="vertical" aria-label={`${label} 너비 조절`} title="끌어서 열 너비 조절 · 더블클릭하면 원래대로"
      onMouseDown={(e) => startColResize(e, c)} onClick={(e) => e.stopPropagation()}
      onDoubleClick={(e) => { e.stopPropagation(); layout.resetWidth(c) }}
      className="absolute -right-[3px] top-0 z-10 h-full w-[6px] cursor-col-resize" />
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col">
    {/* 높이를 고정해 칸을 옮길 때마다 표가 위아래로 출렁이지 않게 한다. 넘치면 이 안에서 스크롤한다.
        아래 경계를 끌면 높이를 바꾸고(이 브라우저에 기억), 더블클릭하면 원래대로(2026-11-13). */}
    <div role="region" aria-label="선택한 칸 내용" style={{ height: layout.barHeight }} className="relative flex shrink-0 border-b border-line text-[13px]">
      <div className="flex w-40 shrink-0 flex-col items-start justify-between border-r border-line bg-tableHead px-2 py-1.5 text-xs text-muted">
        <span>{cell && selRow ? `${selRow.rowNo}행 · ${HEADERS[cell.col]}` : ''}</span>
        {hiddenCols.size > 0 && (
          <button type="button" onClick={layout.showAllCols}
            title={`숨긴 열: ${[...hiddenCols].map((c) => (c === 'place' ? '파일 / 시트' : HEADERS[c])).join(', ')} — 누르면 모두 다시 보입니다`}
            className="press rounded px-1 text-[11px] text-primary hover:bg-primarySoft">
            숨긴 열 {hiddenCols.size}개 · 모두 보이기
          </button>
        )}
      </div>
      {cell && isText(cell.col) && selRow ? (
        // Fix·비고 칸이면 엑셀 수식 입력줄처럼 여기서 바로 고친다. 키는 칸 안 편집 상자와 같다(Enter 저장·Esc 취소).
        <textarea ref={barRef} aria-label="선택한 칸 편집" value={barText ?? ''}
          readOnly={cell.col === 'fix' && (busy.has(selRow.id) || translating.has(selRow.id))}
          onFocus={onBarFocus} onChange={(e) => setEditing({ id: selRow.id, col: cell.col as TextCol, text: e.target.value, where: 'bar' })}
          onKeyDown={onEditorKey} onBlur={onEditorBlur}
          className="min-w-0 flex-1 resize-none break-keep bg-transparent px-2 py-1.5 leading-[1.45] outline-none [overflow-wrap:anywhere] focus:bg-white focus:shadow-[inset_0_0_0_2px_#107c41]" />
      ) : barText !== null ? (
        // 원문·영어·기존 한국어·ID는 읽기 전용 글 상자로 — 눌러서 ⌘A·⌘C를 하면 Fix 입력줄처럼 이 안 글만 잡힌다. Esc는 표로.
        <textarea aria-label="선택한 칸 내용 보기" readOnly value={barText}
          onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); box.current?.focus() } }}
          className="min-w-0 flex-1 resize-none break-keep bg-transparent px-2 py-1.5 leading-[1.45] outline-none [overflow-wrap:anywhere] focus:bg-white focus:shadow-[inset_0_0_0_2px_#d0d5dd]" />
      ) : (
        <div className="min-w-0 flex-1 select-text overflow-y-auto whitespace-pre-wrap break-keep px-2 py-1.5 leading-[1.45] [overflow-wrap:anywhere]">
          {barText ?? <span className="text-muted">칸을 고르면 전체 내용이 여기 보입니다</span>}
        </div>
      )}
      <div role="separator" aria-orientation="horizontal" aria-label="입력줄 높이 조절" title="끌어서 입력줄 높이 조절 · 더블클릭하면 원래대로"
        onMouseDown={startBarResize} onDoubleClick={(e) => { e.stopPropagation(); layout.resetBarHeight() }}
        className="absolute inset-x-0 -bottom-[3px] z-30 h-[6px] cursor-row-resize" />
    </div>
    <div ref={box} role="grid" tabIndex={0} aria-rowcount={total} onKeyDown={onKeyDown} onPaste={onPaste} onScroll={(e) => onBoxScroll(e.currentTarget.scrollTop)}
      className="min-h-0 flex-1 overflow-auto outline-none">
      {/* table-fixed는 표 너비가 정해져 있어야 먹는다. w-max(내용 너비)면 스크롤로 보이는 행이 바뀔 때마다 열 너비가 출렁인다.
          표 너비 = 열 너비의 합(열 너비는 머리글 경계를 끌어 바꾼다). */}
      <table className="table-fixed select-none border-collapse text-[13px]" style={{ width: tableWidth }}>
        <colgroup>{colOrder.map((c) => <col key={c} data-col={c} style={{ width: widths[c] }} />)}</colgroup>
        <thead className="sticky top-0 z-20 bg-tableHead text-left text-xs font-semibold text-body">
          <tr role="row">
            {shownCols.map((c) => <th key={c} role="columnheader" aria-label={HEADERS[c]} onContextMenu={(e) => openColMenu(e, c)}
              title={c === 'confirmed' ? '확정한 행은 같은 원문 자동 반영·단어장 일괄 적용·되돌리기에서 빠집니다(조사 고침은 확정 행도 고칩니다). 더블클릭하면 이 보기의 행을 한꺼번에 체크·해제합니다'
                : isMark(c) ? '더블클릭하면 이 보기의 행을 한꺼번에 체크·해제합니다' : undefined}
              onDoubleClick={isMark(c) ? (e) => { const r = e.currentTarget.getBoundingClientRect(); setBulk({ x: r.left, y: r.bottom + 4, key: c }) } : undefined}
              className={`${isMark(c) ? 'cursor-pointer select-none' : ''} h-[26px] overflow-hidden text-ellipsis whitespace-nowrap border border-line bg-tableHead px-1.5 ${c === 'idx' || isMark(c) ? 'text-center' : ''} ${c === 'idx' ? 'sticky left-0 z-30' : c === 'id' ? 'sticky left-16 z-30' : 'relative'}`}>
              {HEADERS[c]}{c !== 'idx' && widthHandle(c, HEADERS[c])}</th>)
              .flatMap((th, k) => (placeOf && show('place') && shownCols[k] === (show('id') ? 'id' : 'idx') ? [th, <th key="place" role="columnheader" aria-label="파일 / 시트" onContextMenu={(e) => openColMenu(e, 'place')} className="relative h-[26px] overflow-hidden text-ellipsis whitespace-nowrap border border-line bg-tableHead px-1.5">파일 / 시트{widthHandle('place', '파일 / 시트')}</th>] : [th]))}
          </tr>
        </thead>
        <tbody>
          {start > 0 && <tr aria-hidden style={{ height: offsets[Math.min(start, total)] }}><td colSpan={colOrder.length} /></tr>}
          {visible.map((i) => {
            const r = rows[i]
            const sel = cell?.row === i ? cell.col : null
            // 다른 사람이 고친 행 강조: 켜질 땐 바로(transition-none), 꺼질 땐 1초에 걸쳐 사라진다 — 전환은 도착 상태의 것이 쓰인다
            const hl = r && highlighted.has(r.id) ? 'bg-[#fff3a3] transition-none' : 'transition-colors duration-1000'
            // 높인 행은 글을 여러 줄로 감고 위에 붙인다. 행 높이를 넘는 글은 잘라 표의 행 위치 계산이 맞게 한다.
            const h = rowHeight
            const tall = h > ROW_HEIGHT
            const clip = (node: ReactNode) => (tall ? <div className="overflow-hidden" style={{ maxHeight: h - 2 }}>{node}</div> : node)
            // 범위가 있는 동안은 고른 칸도 글자 선택을 닫는다 — 끌어 범위를 만들 때 브라우저 글자 선택이 따라 번지지 않게.
            const cellCls = (c: Col, extra = '') =>
              `${tall ? 'whitespace-normal break-keep align-top [overflow-wrap:anywhere]' : 'h-[26px] text-ellipsis whitespace-nowrap'} overflow-hidden border border-line px-1.5 ${sel === c ? `${range ? '' : 'select-text '}outline outline-2 -outline-offset-2 outline-[#107c41]` : ''} ${extra}`
            const ranged = (c: Col) => inRange(range, i, c)
            const RANGE_BG = 'bg-[#e6f4ea]'
            // 범위 바깥 둘레만 초록 테두리(엑셀처럼): 양옆은 모든 행, 위는 첫 행, 아래는 끝 행. border-collapse 표라
            // 칸 테두리를 바꾸면 옆 칸과 겹쳐 두께가 흔들리므로 칸 안쪽 그림자로 그린다.
            const rangeEdge = (c: Col): { 'data-range-edge'?: string; style?: CSSProperties } => {
              if (!range || !ranged(c)) return {}
              const [lo, hi] = rangeBounds(range)
              const edge = lo === hi ? 'single' : i === lo ? 'top' : i === hi ? 'bottom' : 'mid'
              const g = '#107c41'
              const sides = [`inset 2px 0 0 ${g}`, `inset -2px 0 0 ${g}`]
              if (edge === 'top' || edge === 'single') sides.push(`inset 0 2px 0 ${g}`)
              if (edge === 'bottom' || edge === 'single') sides.push(`inset 0 -2px 0 ${g}`)
              return { 'data-range-edge': edge, style: { boxShadow: sides.join(', ') } }
            }
            if (!r) {
              return (
                <tr key={i} role="row" style={{ height: rowHeight }} className="text-muted">
                  <td role="gridcell" className={cellCls('idx', 'sticky left-0 bg-tableHead text-center')}>{i + 1}</td>
                  {placeOf && show('place') && !show('id') && <td className="border border-line px-1.5">…</td>}
                  {shownCols.slice(1).map((c) => <td key={c} role="gridcell" className={cellCls(c, c === 'id' ? 'sticky left-16 z-10 bg-white' : ranged(c) ? RANGE_BG : '')} {...rangeEdge(c)} onMouseDown={pick(i, c)}
                    onMouseEnter={isRangeCol(c) ? dragInto(i, c) : undefined}
                    onContextMenu={isRangeCol(c) ? (e) => { openRangeMenu(e, i, c) } : undefined}>…</td>)
                    .flatMap((td, k) => (placeOf && show('place') && shownCols[k + 1] === 'id' ? [td, <td key="place" className="border border-line px-1.5">…</td>] : [td]))}
                </tr>
              )
            }
            const isBusy = busy.has(r.id)
            const isTranslating = translating.has(r.id)
            const failed = failedText.get(r.id)
            const displayFix = failed?.text ?? r.fix
            const flagged = lengthFlagged(r.src, displayFix, ratio)
            const fixEditing = editing?.id === r.id && editing.col === 'fix' ? editing : null
            const noteEditing = editing?.id === r.id && editing.col === 'note' ? editing : null
            return (
              <tr key={r.id} role="row" data-row={i} style={{ height: h }} className={hl}>
                <td role="gridcell" aria-label={String(r.rowNo)} className={cellCls('idx', 'sticky left-0 z-10 bg-tableHead text-center text-muted')} onMouseDown={pick(i, 'idx')}
                  style={editorTint(r.lastEditor?.color)} title={r.lastEditor ? `마지막 수정: ${r.lastEditor.name}${r.lastEditedAt ? ` · ${relativeTime(r.lastEditedAt)}` : ''}` : undefined}
                  onContextMenu={(e) => onCellContextMenu(e, 'KO', '', { history: r })}>
                  {r.rowNo}
                  <div role="separator" aria-orientation="horizontal" aria-label={`${r.rowNo}행 높이 조절`} title="끌어서 행 높이 조절 · 더블클릭하면 원래대로"
                    onMouseDown={startRowResize} onClick={(e) => e.stopPropagation()}
                    onDoubleClick={(e) => { e.stopPropagation(); layout.resetRowHeight() }}
                    className="absolute inset-x-0 -bottom-[3px] z-10 h-[6px] cursor-row-resize" />
                </td>
                {show('id') && <td role="gridcell" className={cellCls('id', 'sticky left-16 z-10 bg-white font-mono text-xs text-body')} onMouseDown={pick(i, 'id')}>{clip(<span data-cell-text="id">{r.keyText ?? r.indexLabel}</span>)}</td>}
                {placeOf && show('place') && (() => {
                  // 바로 앞 행과 같은 파일·시트면 옅게 — 경계만 눈에 띄게 한다
                  const place = placeOf(r)
                  const prev = rows[i - 1]
                  const same = !!prev && placeOf(prev) === place
                  return <td className={`${tall ? 'whitespace-normal align-top [overflow-wrap:anywhere]' : 'h-[26px] text-ellipsis whitespace-nowrap'} overflow-hidden border border-line px-1.5 text-xs ${same ? 'text-muted/50' : 'text-body'}`} title={place}>{clip(place)}</td>
                })()}
                {show('src') && <td role="gridcell" className={cellCls('src', ranged('src') ? RANGE_BG : '')} {...rangeEdge('src')} onMouseDown={pick(i, 'src')} onMouseEnter={dragInto(i, 'src')}
                  onContextMenu={(e) => { if (openRangeMenu(e, i, 'src')) return; onCellContextMenu(e, 'SRC', r.src, { row: r, history: r }) }} title={r.src}>
                  {/* 원문만 줄여 …로 자르고 ×N 칩은 줄이지 않는다 — 칸 전체를 자르면 긴 원문 뒤의 칩까지 잘려 안 보인다. */}
                  <div className={tall ? 'overflow-hidden' : 'flex items-center'} style={tall ? { maxHeight: h - 2 } : undefined}>
                    <span data-cell-text="src" className={tall ? '' : 'min-w-0 truncate'}>{r.src}</span>
                    {r.dupCount > 1 && (
                      <button type="button" title="이 원문이 이 게임에서 쓰이는 횟수" aria-haspopup="menu"
                        // 칩을 눌러도 칸을 고르지 않는다 — 모아 보기를 풀 때 돌아갈 자리가 이 칸으로 바뀌면 안 된다.
                        // 누르면 같은 행 / 포함된 행 중 고르는 작은 메뉴를 칩 아래에 띄운다(우클릭 메뉴와 같은 틀).
                        onMouseDown={(e) => e.stopPropagation()}
                        onClick={(e) => { e.stopPropagation(); const b = e.currentTarget.getBoundingClientRect(); setMenu({ x: b.left, y: b.bottom + 4, kind: 'SRC', text: '', whole: true, dup: r.src }) }}
                        className="ml-1 shrink-0 rounded bg-purpleSoft px-1 text-[11px] tracking-[0.01em] text-purple">
                        ×{r.dupCount}
                      </button>
                    )}
                  </div>
                </td>}
                {show('en') && <td role="gridcell" className={cellCls('en')} onMouseDown={pick(i, 'en')} title={r.en ?? ''}>{clip(<span data-cell-text="en">{r.en}</span>)}</td>}
                {show('existingKo') && <td role="gridcell" className={cellCls('existingKo', ranged('existingKo') ? RANGE_BG : '')} {...rangeEdge('existingKo')}
                  onMouseDown={pick(i, 'existingKo')} onMouseEnter={dragInto(i, 'existingKo')} title={r.existingKo ?? ''}
                  onContextMenu={(e) => { if (openRangeMenu(e, i, 'existingKo')) return; onCellContextMenu(e, 'KO', r.existingKo ?? '', { shorten: r, history: r }) }}>{clip(<span data-cell-text="existingKo">{r.existingKo}</span>)}</td>}
                <td role="gridcell" aria-label={`${r.rowNo}행 Fix`}
                  className={cellCls('fix', `relative ${ranged('fix') ? RANGE_BG : finalReview ? (r.finalMark ? 'bg-[#e6f4d7]' : '') : (r.changed ? 'bg-[#fff7d6]' : '')} ${fixEditing ? 'whitespace-normal' : ''} ${failed !== undefined ? 'border-2 border-red-500' : ''}`)}
                  {...rangeEdge('fix')} onMouseDown={pick(i, 'fix')} onMouseEnter={dragInto(i, 'fix')} onDoubleClick={() => startEdit(i)}
                  onContextMenu={(e) => { if (fixEditing) return; if (openRangeMenu(e, i, 'fix')) return; onCellContextMenu(e, 'KO', displayFix ?? '', { shorten: r, history: r }) }}>
                  {fixEditing && fixEditing.where === 'cell' ? (
                    <textarea aria-label={`${r.rowNo}행 Fix`} autoFocus value={fixEditing.text} rows={2}
                      onChange={(e) => setEditing({ id: r.id, col: 'fix', text: e.target.value, where: 'cell' })} onKeyDown={onEditorKey}
                      onBlur={onEditorBlur}
                      className="absolute inset-0 z-10 w-full resize-none border-2 border-[#107c41] bg-white px-1 py-0.5 text-[13px] leading-snug outline-none" />
                  ) : isTranslating ? <span className="italic text-muted">번역 중…</span> : isBusy ? <span className="italic text-muted">저장 중…</span> : clip(
                    <>
                      {r.termMiss.length > 0 && (
                        // 점 둘레로 마우스를 받는 자리를 넓힌다(-m로 글자 위치는 그대로).
                        <span aria-label={`용어 불일치: ${r.termMiss.join(', ')}`} className="-my-1 -ml-1 mr-0 inline-block cursor-help p-1 align-middle"
                          onMouseEnter={(e) => { const b = e.currentTarget.getBoundingClientRect(); setTermTip({ x: b.left, y: b.bottom + 4, items: r.termMiss }) }}
                          onMouseLeave={() => setTermTip(null)}>
                          <span className="block h-2 w-2 rounded-full bg-primary" />
                        </span>
                      )}
                      <span data-cell-text="fix">{fixEditing ? fixEditing.text : displayFix}</span>
                      {(flagged || r.josaHints.length > 0) && (
                        <span className="absolute right-1 top-1 flex gap-0.5">
                          {r.josaHints.length > 0 && (
                            <button type="button" aria-label={`조사 제안 ${r.josaHints.length}개`}
                              onMouseDown={(e) => e.stopPropagation()}
                              onClick={(e) => { e.stopPropagation(); const b = e.currentTarget.getBoundingClientRect(); setJosaTip({ x: b.left, y: b.bottom + 4, row: r }) }}
                              className="press rounded bg-purpleSoft px-1 text-[11px] tracking-[0.01em] text-purple">조사</button>
                          )}
                          {flagged && <span className="rounded bg-orangeSoft px-1 text-[11px] tracking-[0.01em] text-orange">길이</span>}
                        </span>
                      )}
                    </>,
                  )}
                </td>
                {MARKS.filter(show).map((key) => (
                  // 칸 어디를 눌러도 체크가 바뀐다. 확인 필요가 켜진 행은 체크 칸을 옅은 주황으로.
                  <td key={key} role="gridcell" aria-label={`${r.rowNo}행 ${HEADERS[key]}`}
                    className={cellCls(key, `cursor-pointer text-center ${r.needsCheck ? 'bg-orangeSoft' : ''}`)}
                    onMouseDown={pick(i, key)} onClick={() => toggleMark(r, key)}>
                    <input type="checkbox" aria-label={`${r.rowNo}행 ${HEADERS[key]}`} checked={r[key]} onChange={() => { /* 칸의 onClick이 바꾼다 */ }}
                      tabIndex={-1} className="h-[18px] w-[18px] cursor-pointer align-middle accent-[#107c41]" />
                  </td>
                ))}
                {show('note') && <td role="gridcell" aria-label={`${r.rowNo}행 비고`} title={r.note ?? ''}
                  className={cellCls('note', `relative ${noteEditing ? 'whitespace-normal' : ''}`)}
                  onMouseDown={pick(i, 'note')} onDoubleClick={() => startEdit(i, undefined, 'cell', 'note')}>
                  {noteEditing && noteEditing.where === 'cell' ? (
                    <textarea aria-label={`${r.rowNo}행 비고`} autoFocus value={noteEditing.text} rows={2}
                      onChange={(e) => setEditing({ id: r.id, col: 'note', text: e.target.value, where: 'cell' })} onKeyDown={onEditorKey}
                      onBlur={onEditorBlur}
                      className="absolute inset-0 z-10 w-full resize-none border-2 border-[#107c41] bg-white px-1 py-0.5 text-[13px] leading-snug outline-none" />
                  ) : clip(<span data-cell-text="note">{noteEditing ? noteEditing.text : r.note}</span>)}
                </td>}
              </tr>
            )
          })}
          {end < total && <tr aria-hidden style={{ height: offsets[total] - offsets[end] }}><td colSpan={colOrder.length} /></tr>}
        </tbody>
      </table>
      {menu && (
        <Anchored ref={menuRef} role="menu" x={menu.x} y={menu.y} deps={[menu.row, menu.shorten, menu.history, menu.hideCol, menu.range, menu.copyAllFix]}
          className="glass pop-in z-50 min-w-[180px] origin-top-left rounded-lg border border-line/70 p-1 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
          {menu.range && (() => {
            const n = menu.range.rows.length
            // 너무 많거나 안 들어온 행이 끼어 있으면 끈다(이유는 title로)
            const why = n > RANGE_ACTION_MAX ? `한 번에 ${RANGE_ACTION_MAX}행까지 됩니다`
              : menu.range.rows.some((r) => r === undefined) ? '스크롤해 행을 불러온 뒤 다시 하세요' : undefined
            const item = (action: 'deepl' | 'gpt' | 'polish', label: string) => (
              <button key={action} type="button" role="menuitem" disabled={why !== undefined} title={why}
                className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6] disabled:cursor-not-allowed disabled:text-muted disabled:hover:bg-transparent"
                onClick={() => runRangeAction(action)}>
                {label} ({n}행)
              </button>
            )
            // 복사는 행 수 제한이 없다 — 안 들어온 행만 막는다
            const unloaded = menu.range.rows.some((r) => r === undefined)
            const { col, rows: rs } = menu.range
            const copy = (
              <button key="copy" type="button" role="menuitem" disabled={unloaded} title={unloaded ? '스크롤해 행을 불러온 뒤 다시 하세요' : undefined}
                className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6] disabled:cursor-not-allowed disabled:text-muted disabled:hover:bg-transparent"
                onClick={() => { setMenu(null); copyRange(col, rs) }}>
                복사 ({n}행)
              </button>
            )
            return <>{copy}{col === 'src' ? <>{item('deepl', 'DeepL 번역')}{item('gpt', 'GPT 번역')}</> : col === 'fix' ? item('polish', 'GPT로 다듬기') : null}</>
          })()}
          {menu.copyAllFix && (
            <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
              onClick={() => { setMenu(null); onCopyAllFix() }}>
              Fix 열 전체 복사
            </button>
          )}
          {menu.dup !== undefined && (
            <>
              <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
                onClick={() => { onFilterDuplicates(menu.dup!, 'exact'); setMenu(null) }}>
                같은 행 보기
              </button>
              <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
                onClick={() => { onFilterDuplicates(menu.dup!, 'contains'); setMenu(null) }}>
                포함된 전체 행 보기
              </button>
            </>
          )}
          {menu.text && (
            // 칸 하나 복사: 칸 안에서 글자를 긁어 두었으면 그 부분, 아니면 칸 전체 값(Ctrl+C와 같다)
            <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
              onClick={() => { copyText(menu.text); setMenu(null) }}>
              복사
            </button>
          )}
          {menu.text && (
            <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
              onClick={() => { onAddToGlossary(menu.kind, menu.text, menu.whole); setMenu(null) }}>
              {menu.kind === 'SRC' ? '단어장에 추가 (중국어)' : '단어장에 추가 (한국어)'}
            </button>
          )}
          {menu.shorten && (
            <>
              <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
                onClick={openShorten}>
                짧게 줄이기
              </button>
            </>
          )}
          {menu.row && (
            <>
              <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
                onClick={translateRow}>
                DeepL 번역
              </button>
            </>
          )}
          {menu.history && (
            <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
              onClick={openHistory}>
              변경 내역 보기
            </button>
          )}
          {menu.hideCol && (
            <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
              onClick={hideCol}>
              열 숨기기
            </button>
          )}
        </Anchored>
      )}
      {historyOf && (
        <RowHistoryPopover key={historyOf.row.id} x={historyOf.x} y={historyOf.y} rowNo={historyOf.row.rowNo}
          load={() => onHistory(historyOf.row)} current={historyRow!.fix}
          onRestore={busy.has(historyRow!.id) || translating.has(historyRow!.id) ? undefined : (text) => onSave(historyRow!, text)}
          onClose={closeHistory} />
      )}
      {josaTip && (
        <JosaPopover x={josaTip.x} y={josaTip.y} fix={josaTip.row.fix ?? ''} hints={josaTip.row.josaHints}
          onApply={() => { onJosaApply(josaTip.row); setJosaTip(null) }}
          onIgnore={() => { onJosaIgnore(josaTip.row); setJosaTip(null) }}
          onClose={() => setJosaTip(null)} />
      )}
      {termTip && (
        <Anchored role="tooltip" x={termTip.x} y={termTip.y} deps={[termTip.items]}
          className="glass pointer-events-none z-50 max-w-xs origin-top-left rounded-lg border border-line/70 px-2.5 py-1.5 text-[12px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
          <div className="mb-0.5 text-[11px] font-medium text-muted">용어 불일치 — 확정 번역이 Fix에 없음</div>
          <ul>
            {termTip.items.map((it) => {
              const [src, ko] = it.split('→')
              return <li key={it} className="whitespace-nowrap">{src} <span className="text-muted">→</span> <span className="font-medium text-primary">{ko ?? ''}</span></li>
            })}
          </ul>
        </Anchored>
      )}
      {bulk && (
        <BulkMarkPopover x={bulk.x} y={bulk.y} label={HEADERS[bulk.key]} count={viewCount}
          onApply={(on) => { onBulkMarks(bulk.key, on); closeBulk() }} onClose={closeBulk} />
      )}
      {shortening && (
        <ShortenPopover key={shortening.row.id} x={shortening.x} y={shortening.y}
          onShorten={(maxChars) => onShorten(shortening.row, maxChars)} onPick={pickShortened} onClose={closeShorten} />
      )}
    </div>
    </div>
  )
}
