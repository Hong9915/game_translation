import { useCallback, useState } from 'react'
import type { Col } from './gridNav'

/**
 * 검수 표의 보기 설정(2026-10-12): 숨긴 열, 행 높이(모든 행 같이), 열 너비. 서버에 저장하지 않고 이 브라우저에만 기억한다 —
 * 다른 사람 화면·내보내기·일괄 도구에는 영향이 없다. 파일 화면과 게임 화면이 같이 쓴다.
 */
export type WidthCol = Col | 'place'
export type ColWidths = Record<WidthCol, number>

export const DEFAULT_WIDTHS: ColWidths = {
  idx: 48, id: 128, place: 160, src: 224, en: 208, existingKo: 224, fix: 288, review1: 56, review2: 56, needsCheck: 56, confirmed: 56, finalMark: 64, note: 192,
}
export const MIN_WIDTH = 40
export const DEFAULT_ROW_HEIGHT = 26
export const MAX_ROW_HEIGHT = 400
/** 숨길 수 없는 열: # 칸(행 메뉴·높이 손잡이)과 Fix(편집하는 열). */
export const UNHIDEABLE: ReadonlySet<WidthCol> = new Set<WidthCol>(['idx', 'fix'])

const HIDDEN_COLS_KEY = 'gametrans.gridHiddenCols'
const ROW_HEIGHT_KEY = 'gametrans.gridRowHeight'
const WIDTHS_KEY = 'gametrans.gridColWidths'

function read(key: string): unknown {
  try { return JSON.parse(localStorage.getItem(key) ?? 'null') } catch { return null }
}
function write(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* 기억만 못 한다 */ }
}

function loadHiddenCols(): Set<WidthCol> {
  const v = read(HIDDEN_COLS_KEY)
  return new Set(Array.isArray(v) ? v.filter((x): x is WidthCol => typeof x === 'string' && x in DEFAULT_WIDTHS && !UNHIDEABLE.has(x as WidthCol)) : [])
}
const clampRowHeight = (h: number) => Math.min(MAX_ROW_HEIGHT, Math.max(DEFAULT_ROW_HEIGHT, Math.round(h)))
/** 기억한 행 높이. 검색에서 돌아갈 때 등 표 밖에서 스크롤 위치를 셀 때도 쓴다. */
export function loadRowHeight(): number {
  const v = read(ROW_HEIGHT_KEY)
  return typeof v === 'number' && Number.isFinite(v) ? clampRowHeight(v) : DEFAULT_ROW_HEIGHT
}
function loadWidths(): ColWidths {
  const v = read(WIDTHS_KEY)
  const w = { ...DEFAULT_WIDTHS }
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    for (const k of Object.keys(w) as WidthCol[]) {
      const x = (v as Record<string, unknown>)[k]
      if (typeof x === 'number' && Number.isFinite(x)) w[k] = Math.max(MIN_WIDTH, Math.round(x))
    }
  }
  return w
}

export function useGridLayout() {
  const [hiddenCols, setHiddenCols] = useState(loadHiddenCols)
  const [rowHeight, setRowHeightState] = useState(loadRowHeight)
  const [widths, setWidths] = useState(loadWidths)

  const updateHiddenCols = useCallback((f: (s: Set<WidthCol>) => void) => setHiddenCols((prev) => {
    const next = new Set(prev); f(next); write(HIDDEN_COLS_KEY, [...next]); return next
  }), [])
  const updateWidths = useCallback((f: (w: ColWidths) => void) => setWidths((prev) => {
    const next = { ...prev }; f(next); write(WIDTHS_KEY, next); return next
  }), [])

  return {
    hiddenCols, rowHeight, widths,
    hideCol: useCallback((c: WidthCol) => { if (!UNHIDEABLE.has(c)) updateHiddenCols((s) => s.add(c)) }, [updateHiddenCols]),
    showCol: useCallback((c: WidthCol) => updateHiddenCols((s) => s.delete(c)), [updateHiddenCols]),
    showAllCols: useCallback(() => updateHiddenCols((s) => s.clear()), [updateHiddenCols]),
    setRowHeight: useCallback((h: number) => { const v = clampRowHeight(h); write(ROW_HEIGHT_KEY, v); setRowHeightState(v) }, []),
    resetRowHeight: useCallback(() => { write(ROW_HEIGHT_KEY, DEFAULT_ROW_HEIGHT); setRowHeightState(DEFAULT_ROW_HEIGHT) }, []),
    setWidth: useCallback((c: WidthCol, w: number) => updateWidths((x) => { x[c] = Math.max(MIN_WIDTH, Math.round(w)) }), [updateWidths]),
    resetWidth: useCallback((c: WidthCol) => updateWidths((x) => { x[c] = DEFAULT_WIDTHS[c] }), [updateWidths]),
  }
}

/** 행 높이 목록 → 각 행의 위쪽 위치(길이 n+1, 마지막은 전체 높이). */
export function rowOffsets(heights: number[]): number[] {
  const out = new Array<number>(heights.length + 1)
  out[0] = 0
  for (let i = 0; i < heights.length; i++) out[i + 1] = out[i] + heights[i]
  return out
}

/** y 위치에 걸친 행 번호(이진 탐색). 끝을 넘으면 마지막 행, 행이 없으면 0. */
export function indexAt(offsets: number[], y: number): number {
  const n = offsets.length - 1
  if (n <= 0) return 0
  let lo = 0, hi = n - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (offsets[mid] <= y) lo = mid
    else hi = mid - 1
  }
  return lo
}
