import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ClipboardEvent, type CSSProperties, type FocusEvent, type KeyboardEvent, type MouseEvent } from 'react'
import type { GlossaryKind, MarkKey, MarksPatch, RowHistoryEntry, RowShortenResult, WorkspaceRow } from '../../api/types'
import { COLS, distributePaste, move, nextFixRow, type ArrowKey, type Cell, type Col } from '../../lib/gridNav'
import { lengthFlagged } from '../../lib/lengthCheck'
import { relativeTime } from '../../lib/relativeTime'
import { Anchored } from '../ui/Anchored'
import { BulkMarkPopover } from './BulkMarkPopover'
import { JosaPopover } from './JosaPopover'
import { RowHistoryPopover } from './RowHistoryPopover'
import { ShortenPopover } from './ShortenPopover'

export const ROW_HEIGHT = 26
const OVERSCAN = 50
const FALLBACK_VIEW_ROWS = 40   // jsdom처럼 높이를 모를 때

export interface WorkspaceGridProps {
  rows: (WorkspaceRow | undefined)[]
  ratio: number
  busy: Set<number>
  highlighted: Set<number>
  /** 저장 실패로 아직 반영 안 된 입력(세그먼트 id → { 입력한 텍스트, 그때의 r.fix }). r.fix 대신 text를 보여주고
   * 빨간 테두리를 준다(설계 §5). base는 그 행이 다른 곳(SSE 등)에서 바뀌었는지 부모가 판단하는 데 쓰는 값이라
   * 그리드는 base를 보지 않는다 — 항목이 남아 있으면 그냥 실패 표시를 한다. */
  failedText: Map<number, { text: string; base: string }>
  onSave: (row: WorkspaceRow, text: string) => void
  /** 1차·2차·확인 필요·확정 체크와 비고 변경. 준 칸만 바꾼다(서로 독립). */
  onMarks: (row: WorkspaceRow, patch: MarksPatch) => void
  /** 체크 머리글 더블클릭 → 일괄 체크 창에서 고르면 부른다. 대상(지금 보기)은 부모가 정한다. */
  onBulkMarks: (key: MarkKey, on: boolean) => void
  /** 일괄 체크가 적용될 행 수(쪽 보기면 그 쪽, 전체 보기면 조건에 걸린 전부). 창에 보인다. */
  viewCount: number
  /** 원문 셀의 ×N 칩을 누르면 그 원문으로 검색어(중복 필터)를 건다. */
  onFilterDuplicates: (src: string) => void
  /** 원문·Fix 셀 우클릭 메뉴에서 "단어장에 추가"를 고르면 부른다. whole=true면 셀 전체 값, false면 그 안의 선택 영역. */
  onAddToGlossary: (kind: GlossaryKind, text: string, whole: boolean) => void
  /** 원문 셀 우클릭 메뉴의 "GPT 번역"에서 추가 지시(빈 문자열 가능)를 넣고 번역을 누르면 부른다. 결과는 그 행의 Fix를 덮어쓴다. */
  onTranslate: (row: WorkspaceRow, instructions: string) => void
  /** 기존 한국어·Fix 셀 우클릭 "짧게 줄이기"에서 GPT 후보를 받는다(저장 안 함). 고른 후보는 onSave로 저장한다. */
  onShorten: (row: WorkspaceRow, maxChars?: number) => Promise<RowShortenResult>
  /** Fix 셀 `조사` 배지 창의 [적용] — 그 행의 제안을 종류 상관없이 모두 고친다. */
  onJosaApply: (row: WorkspaceRow) => void
  /** 같은 창의 [무시] — 번역이 다시 바뀌기 전까지 이 행 제안을 숨긴다. */
  onJosaIgnore: (row: WorkspaceRow) => void
  /** 우클릭 "변경 내역 보기" — 그 행의 판을 최신부터 받는다. */
  onHistory: (row: WorkspaceRow) => Promise<RowHistoryEntry[]>
  /** GPT 번역 응답을 기다리는 행. Fix 칸에 "GPT 번역 중…"을 보이고 편집을 막는다. */
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
  review1: 'A', review2: 'B', needsCheck: '확인 필요', confirmed: '확정', note: '비고',
}
const WIDTHS: Record<Col, string> = {
  idx: 'w-12', id: 'w-32', src: 'w-56', en: 'w-52', existingKo: 'w-56', fix: 'w-72', review1: 'w-14', review2: 'w-14', needsCheck: 'w-14', confirmed: 'w-14', note: 'w-48',
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

const MARKS: MarkKey[] = ['review1', 'review2', 'needsCheck', 'confirmed']
const isMark = (c: Col): c is MarkKey => (MARKS as string[]).includes(c)
/** 글을 편집하는 칸. Fix는 저장(onSave), 비고는 onMarks로 간다. */
type TextCol = 'fix' | 'note'
const isText = (c: Col): c is TextCol => c === 'fix' || c === 'note'
const ARROWS: ArrowKey[] = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']
// 직전에 쓴 GPT 번역 추가 지시. 브라우저마다 따로 기억한다 — 비공개 창 등에서 저장소가 막혀도 번역은 된다.
const INSTRUCTIONS_KEY = 'gametrans.rowTranslateInstructions'
const loadInstructions = () => { try { return localStorage.getItem(INSTRUCTIONS_KEY) ?? '' } catch { return '' } }
const storeInstructions = (text: string) => { try { localStorage.setItem(INSTRUCTIONS_KEY, text) } catch { /* 기억만 못 한다 */ } }

/** 엑셀식 검수 표. 조회·저장은 부모(WorkspacePage)가 하고, 여기서는 선택·편집·가상 스크롤만 다룬다. */
export function WorkspaceGrid({ rows, ratio, busy, highlighted, failedText, onSave, onMarks, onBulkMarks, viewCount, onFilterDuplicates, onAddToGlossary, onTranslate, onShorten, onJosaApply, onJosaIgnore, onHistory, translating, onVisibleRange, onFocusCell, onSelect, onPosition, restore, onRestored }: WorkspaceGridProps) {
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
  // row는 원문 셀에서 연 메뉴에만 있다 — "GPT 번역" 항목이 그 행을 번역한다.
  // shorten은 기존 한국어·Fix 셀에서 연 메뉴에만 있다 — "짧게 줄이기" 항목이 그 행의 한국어를 줄인다.
  // history는 # 칸·원문·기존 한국어·Fix 칸 메뉴에 있다 — "변경 내역 보기". # 칸 메뉴는 text가 비어 단어장 항목이 없다.
  const [menu, setMenu] = useState<{ x: number; y: number; kind: GlossaryKind; text: string; whole: boolean; row?: WorkspaceRow; shorten?: WorkspaceRow; history?: WorkspaceRow } | null>(null)
  // 우클릭 "변경 내역 보기" 창. 메뉴와 같은 자리에 뜬다.
  const [historyOf, setHistoryOf] = useState<{ x: number; y: number; row: WorkspaceRow } | null>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  // GPT 번역 추가 지시 창. 메뉴와 같은 자리에 뜬다.
  const [ask, setAsk] = useState<{ x: number; y: number; row: WorkspaceRow; text: string } | null>(null)
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
  const viewRows = viewHeight > 0 ? Math.ceil(viewHeight / ROW_HEIGHT) : FALLBACK_VIEW_ROWS
  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN)
  const end = Math.min(total, Math.floor(scrollTop / ROW_HEIGHT) + viewRows + OVERSCAN)   // exclusive
  useEffect(() => { onVisibleRange(start, end) }, [start, end, onVisibleRange])

  // 선택한 행이 보이도록 스크롤
  const scrollIntoView = (r: number) => {
    const el = box.current
    if (!el || el.clientHeight === 0) return
    const top = r * ROW_HEIGHT, bottom = top + ROW_HEIGHT
    if (top < el.scrollTop) el.scrollTop = top
    else if (bottom > el.scrollTop + el.clientHeight - ROW_HEIGHT) el.scrollTop = bottom - el.clientHeight + ROW_HEIGHT
  }

  const startEdit = (r: number, initial?: string, where: 'cell' | 'bar' = 'cell', col: TextCol = 'fix') => {
    const row = rows[r]
    if (!row) return
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

  const openHistory = () => {
    if (!menu?.history) return
    setHistoryOf({ x: menu.x, y: menu.y, row: menu.history })
    setMenu(null)
  }
  const closeHistory = () => { setHistoryOf(null); box.current?.focus() }
  // 창을 연 뒤 저장·실시간 반영으로 바뀐 행을 쓴다(되돌리기 단추가 지금 본문과 비교한다)
  const historyRow = historyOf ? (rows.find((r) => r?.id === historyOf.row.id) ?? historyOf.row) : null

  const openAsk = () => {
    if (!menu?.row) return
    setAsk({ x: menu.x, y: menu.y, row: menu.row, text: loadInstructions() })
    setMenu(null)
  }
  const closeAsk = () => { setAsk(null); box.current?.focus() }
  const submitAsk = () => {
    if (!ask) return
    const text = ask.text.trim()
    storeInstructions(text)
    onTranslate(ask.row, text)
    closeAsk()
  }
  const onAskKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); submitAsk() }
    else if (e.key === 'Escape') { e.preventDefault(); closeAsk() }
  }

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (editing) return   // textarea가 처리한다
    // 아직 아무 칸도 안 골랐으면 첫 키는 1행 Fix를 고르는 데 쓴다(엑셀의 A1처럼 늘 같은 자리에서 시작)
    if (!cell) { if (total > 0) setCell({ row: 0, col: 'fix' }); return }
    const row = rows[cell.row]
    if (ARROWS.includes(e.key as ArrowKey)) { e.preventDefault(); const c = move(cell, e.key as ArrowKey, total); setCell(c); scrollIntoView(c.row); return }
    if (e.key === 'Tab') {
      e.preventDefault()
      const next = nextFixRow(cell.row, total, e.shiftKey ? -1 : 1)
      if (next !== null) { setCell({ row: next, col: 'fix' }); scrollIntoView(next) }
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
    for (const { index, text } of distributePaste(e.clipboardData.getData('text'), cell.row, rows)) {
      const row = rows[index]
      if (row && text.trim() !== '' && text.trim() !== (row.fix ?? '').trim()) onSave(row, text)
    }
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

  return (
    <div className="flex min-h-0 flex-1 flex-col">
    {/* 높이를 고정해 칸을 옮길 때마다 표가 위아래로 출렁이지 않게 한다. 넘치면 이 안에서 스크롤한다. */}
    <div role="region" aria-label="선택한 칸 내용" className="flex h-[70px] shrink-0 border-b border-line text-[13px]">
      <div className="flex w-40 shrink-0 items-start border-r border-line bg-tableHead px-2 py-1.5 text-xs text-muted">
        {cell && selRow ? `${selRow.rowNo}행 · ${HEADERS[cell.col]}` : ''}
      </div>
      {cell && isText(cell.col) && selRow ? (
        // Fix·비고 칸이면 엑셀 수식 입력줄처럼 여기서 바로 고친다. 키는 칸 안 편집 상자와 같다(Enter 저장·Esc 취소).
        <textarea ref={barRef} aria-label="선택한 칸 편집" value={barText ?? ''}
          readOnly={cell.col === 'fix' && (busy.has(selRow.id) || translating.has(selRow.id))}
          onFocus={onBarFocus} onChange={(e) => setEditing({ id: selRow.id, col: cell.col as TextCol, text: e.target.value, where: 'bar' })}
          onKeyDown={onEditorKey} onBlur={onEditorBlur}
          className="min-w-0 flex-1 resize-none break-keep bg-transparent px-2 py-1.5 leading-[1.45] outline-none [overflow-wrap:anywhere] focus:bg-white focus:shadow-[inset_0_0_0_2px_#107c41]" />
      ) : (
        <div className="min-w-0 flex-1 select-text overflow-y-auto whitespace-pre-wrap break-keep px-2 py-1.5 leading-[1.45] [overflow-wrap:anywhere]">
          {barText ?? <span className="text-muted">칸을 고르면 전체 내용이 여기 보입니다</span>}
        </div>
      )}
    </div>
    <div ref={box} role="grid" tabIndex={0} aria-rowcount={total} onKeyDown={onKeyDown} onPaste={onPaste} onScroll={(e) => onBoxScroll(e.currentTarget.scrollTop)}
      className="min-h-0 flex-1 overflow-auto outline-none">
      {/* table-fixed는 표 너비가 정해져 있어야 먹는다. w-max(내용 너비)면 스크롤로 보이는 행이 바뀔 때마다 열 너비가 출렁인다.
          1480px = WIDTHS의 합(48+128+224+208+224+288+56·3+192). WIDTHS를 바꾸면 같이 바꾼다. */}
      <table className="w-[1480px] table-fixed border-collapse text-[13px]">
        <thead className="sticky top-0 z-20 bg-tableHead text-left text-xs font-semibold text-body">
          <tr role="row">
            {COLS.map((c) => <th key={c} role="columnheader"
              title={c === 'confirmed' ? '확정한 행은 같은 원문 자동 반영·단어장 일괄 적용·되돌리기에서 빠집니다(조사 고침은 확정 행도 고칩니다). 더블클릭하면 이 보기의 행을 한꺼번에 체크·해제합니다'
                : isMark(c) ? '더블클릭하면 이 보기의 행을 한꺼번에 체크·해제합니다' : undefined}
              onDoubleClick={isMark(c) ? (e) => { const r = e.currentTarget.getBoundingClientRect(); setBulk({ x: r.left, y: r.bottom + 4, key: c }) } : undefined}
              className={`${isMark(c) ? 'cursor-pointer select-none' : ''} ${WIDTHS[c]} h-[26px] border border-line bg-tableHead px-1.5 ${c === 'idx' || isMark(c) ? 'text-center' : ''} ${c === 'idx' ? 'sticky left-0 z-30' : c === 'id' ? 'sticky left-12 z-30' : ''}`}>{HEADERS[c]}</th>)}
          </tr>
        </thead>
        <tbody>
          {start > 0 && <tr aria-hidden style={{ height: start * ROW_HEIGHT }}><td colSpan={COLS.length} /></tr>}
          {visible.map((i) => {
            const r = rows[i]
            const sel = cell?.row === i ? cell.col : null
            // 다른 사람이 고친 행 강조: 켜질 땐 바로(transition-none), 꺼질 땐 1초에 걸쳐 사라진다 — 전환은 도착 상태의 것이 쓰인다
            const hl = r && highlighted.has(r.id) ? 'bg-[#fff3a3] transition-none' : 'transition-colors duration-1000'
            const cellCls = (c: Col, extra = '') =>
              `${WIDTHS[c]} h-[26px] overflow-hidden text-ellipsis whitespace-nowrap border border-line px-1.5 ${sel === c ? 'outline outline-2 -outline-offset-2 outline-[#107c41]' : ''} ${extra}`
            if (!r) {
              return (
                <tr key={i} role="row" style={{ height: ROW_HEIGHT }} className="text-muted">
                  <td role="gridcell" className={cellCls('idx', 'sticky left-0 bg-tableHead text-center')}>{i + 1}</td>
                  {COLS.slice(1).map((c) => <td key={c} role="gridcell" className={cellCls(c, c === 'id' ? 'sticky left-12 z-10 bg-white' : '')} onClick={() => setCell({ row: i, col: c })}>…</td>)}
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
              <tr key={r.id} role="row" style={{ height: ROW_HEIGHT }} className={hl}>
                <td role="gridcell" className={cellCls('idx', 'sticky left-0 z-10 bg-tableHead text-center text-muted')} onClick={() => setCell({ row: i, col: 'idx' })}
                  style={editorTint(r.lastEditor?.color)} title={r.lastEditor ? `마지막 수정: ${r.lastEditor.name}${r.lastEditedAt ? ` · ${relativeTime(r.lastEditedAt)}` : ''}` : undefined}
                  onContextMenu={(e) => onCellContextMenu(e, 'KO', '', { history: r })}>{r.rowNo}</td>
                <td role="gridcell" className={cellCls('id', 'sticky left-12 z-10 bg-white font-mono text-xs text-body')} onClick={() => setCell({ row: i, col: 'id' })}>{r.keyText ?? r.indexLabel}</td>
                <td role="gridcell" className={cellCls('src')} onClick={() => setCell({ row: i, col: 'src' })}
                  onContextMenu={(e) => onCellContextMenu(e, 'SRC', r.src, { row: r, history: r })} title={r.src}>
                  {/* 원문만 줄여 …로 자르고 ×N 칩은 줄이지 않는다 — 칸 전체를 자르면 긴 원문 뒤의 칩까지 잘려 안 보인다. */}
                  <div className="flex items-center">
                    <span className="min-w-0 truncate">{r.src}</span>
                    {r.dupCount > 1 && (
                      <button type="button" title="이 원문이 이 게임에서 쓰이는 횟수"
                        onClick={(e) => { e.stopPropagation(); onFilterDuplicates(r.src) }}
                        className="ml-1 shrink-0 rounded bg-purpleSoft px-1 text-[11px] tracking-[0.01em] text-purple">
                        ×{r.dupCount}
                      </button>
                    )}
                  </div>
                </td>
                <td role="gridcell" className={cellCls('en')} onClick={() => setCell({ row: i, col: 'en' })} title={r.en ?? ''}>{r.en}</td>
                <td role="gridcell" className={cellCls('existingKo')} onClick={() => setCell({ row: i, col: 'existingKo' })} title={r.existingKo ?? ''}
                  onContextMenu={(e) => onCellContextMenu(e, 'KO', r.existingKo ?? '', { shorten: r, history: r })}>{r.existingKo}</td>
                <td role="gridcell" aria-label={`${r.rowNo}행 Fix`}
                  className={cellCls('fix', `relative ${r.changed ? 'bg-[#fff7d6]' : ''} ${fixEditing ? 'whitespace-normal' : ''} ${failed !== undefined ? 'border-2 border-red-500' : ''}`)}
                  onClick={() => setCell({ row: i, col: 'fix' })} onDoubleClick={() => startEdit(i)}
                  onContextMenu={(e) => { if (fixEditing) return; onCellContextMenu(e, 'KO', displayFix ?? '', { shorten: r, history: r }) }}>
                  {fixEditing && fixEditing.where === 'cell' ? (
                    <textarea aria-label={`${r.rowNo}행 Fix`} autoFocus value={fixEditing.text} rows={2}
                      onChange={(e) => setEditing({ id: r.id, col: 'fix', text: e.target.value, where: 'cell' })} onKeyDown={onEditorKey}
                      onBlur={onEditorBlur}
                      className="absolute inset-0 z-10 w-full resize-none border-2 border-[#107c41] bg-white px-1 py-0.5 text-[13px] leading-snug outline-none" />
                  ) : isTranslating ? <span className="italic text-muted">GPT 번역 중…</span> : isBusy ? <span className="italic text-muted">저장 중…</span> : (
                    <>
                      {r.termMiss.length > 0 && (
                        // 점 둘레로 마우스를 받는 자리를 넓힌다(-m로 글자 위치는 그대로).
                        <span aria-label={`용어 불일치: ${r.termMiss.join(', ')}`} className="-my-1 -ml-1 mr-0 inline-block cursor-help p-1 align-middle"
                          onMouseEnter={(e) => { const b = e.currentTarget.getBoundingClientRect(); setTermTip({ x: b.left, y: b.bottom + 4, items: r.termMiss }) }}
                          onMouseLeave={() => setTermTip(null)}>
                          <span className="block h-2 w-2 rounded-full bg-primary" />
                        </span>
                      )}
                      {fixEditing ? fixEditing.text : displayFix}
                      {(flagged || r.josaHints.length > 0) && (
                        <span className="absolute right-1 top-1 flex gap-0.5">
                          {r.josaHints.length > 0 && (
                            <button type="button" aria-label={`조사 제안 ${r.josaHints.length}개`}
                              onClick={(e) => { e.stopPropagation(); const b = e.currentTarget.getBoundingClientRect(); setJosaTip({ x: b.left, y: b.bottom + 4, row: r }) }}
                              className="press rounded bg-purpleSoft px-1 text-[11px] tracking-[0.01em] text-purple">조사</button>
                          )}
                          {flagged && <span className="rounded bg-orangeSoft px-1 text-[11px] tracking-[0.01em] text-orange">길이</span>}
                        </span>
                      )}
                    </>
                  )}
                </td>
                {MARKS.map((key) => (
                  // 칸 어디를 눌러도 체크가 바뀐다. 확인 필요가 켜진 행은 체크 칸을 옅은 주황으로.
                  <td key={key} role="gridcell" aria-label={`${r.rowNo}행 ${HEADERS[key]}`}
                    className={cellCls(key, `cursor-pointer text-center ${r.needsCheck ? 'bg-orangeSoft' : ''}`)}
                    onClick={() => { setCell({ row: i, col: key }); toggleMark(r, key) }}>
                    <input type="checkbox" aria-label={`${r.rowNo}행 ${HEADERS[key]}`} checked={r[key]} onChange={() => { /* 칸의 onClick이 바꾼다 */ }}
                      tabIndex={-1} className="h-[18px] w-[18px] cursor-pointer align-middle accent-[#107c41]" />
                  </td>
                ))}
                <td role="gridcell" aria-label={`${r.rowNo}행 비고`} title={r.note ?? ''}
                  className={cellCls('note', `relative ${noteEditing ? 'whitespace-normal' : ''}`)}
                  onClick={() => setCell({ row: i, col: 'note' })} onDoubleClick={() => startEdit(i, undefined, 'cell', 'note')}>
                  {noteEditing && noteEditing.where === 'cell' ? (
                    <textarea aria-label={`${r.rowNo}행 비고`} autoFocus value={noteEditing.text} rows={2}
                      onChange={(e) => setEditing({ id: r.id, col: 'note', text: e.target.value, where: 'cell' })} onKeyDown={onEditorKey}
                      onBlur={onEditorBlur}
                      className="absolute inset-0 z-10 w-full resize-none border-2 border-[#107c41] bg-white px-1 py-0.5 text-[13px] leading-snug outline-none" />
                  ) : noteEditing ? noteEditing.text : r.note}
                </td>
              </tr>
            )
          })}
          {end < total && <tr aria-hidden style={{ height: (total - end) * ROW_HEIGHT }}><td colSpan={COLS.length} /></tr>}
        </tbody>
      </table>
      {menu && (
        <Anchored ref={menuRef} role="menu" x={menu.x} y={menu.y} deps={[menu.row, menu.shorten, menu.history]}
          className="glass pop-in z-50 min-w-[180px] origin-top-left rounded-lg border border-line/70 p-1 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
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
                onClick={openAsk}>
                GPT 번역
              </button>
            </>
          )}
          {menu.history && (
            <button type="button" role="menuitem" className="block w-full rounded-md px-2.5 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]"
              onClick={openHistory}>
              변경 내역 보기
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
      {ask && (
        // 이 창의 키·붙여넣기가 그리드(onKeyDown·onPaste)로 올라가 Fix 편집을 열지 않게 여기서 멈춘다.
        <Anchored role="dialog" aria-label="GPT 번역" x={ask.x} y={ask.y}
          onKeyDown={(e) => e.stopPropagation()} onPaste={(e) => e.stopPropagation()}
          className="glass pop-in z-50 w-72 origin-top-left rounded-xl border border-line/70 p-2.5 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
          <label className="mb-1 block text-xs text-body" htmlFor="row-translate-instructions">추가 지시 (비워도 됨)</label>
          <textarea id="row-translate-instructions" aria-label="추가 지시" autoFocus rows={3} value={ask.text}
            onChange={(e) => setAsk({ ...ask, text: e.target.value })} onKeyDown={onAskKey}
            placeholder="예: 10자 이내로, 존댓말로"
            className="w-full resize-none rounded-lg border border-line bg-white px-2 py-1.5 text-[13px] outline-none transition-shadow focus:border-primary focus:ring-4 focus:ring-primary/10" />
          <div className="mt-1.5 flex items-center justify-between">
            <span className="text-[11px] text-muted">Enter 번역 · Shift+Enter 줄바꿈</span>
            <div className="flex gap-1">
              <button type="button" onClick={closeAsk} className="press rounded-md px-2.5 py-1 text-body hover:bg-black/5">취소</button>
              {/* 셀 편집의 초록은 엑셀 친숙함용이고, 이 창은 셀이 아니므로 앱의 주요 버튼 색(primary)을 쓴다 */}
              <button type="button" onClick={submitAsk} className="press rounded-md bg-primary px-2.5 py-1 font-medium text-white hover:bg-[#1a4084]">번역</button>
            </div>
          </div>
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
