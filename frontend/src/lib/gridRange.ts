/** 범위를 고를 수 있는 열: Fix 칸, 원문 칸, 기존 한국어 칸(복사만, 2026-11-13). */
export type RangeCol = 'fix' | 'src' | 'existingKo'
/** 한 열 안에서 anchor(시작 행)부터 head(끝 행)까지 고른 범위. head가 더 작을 수도 있다. */
export interface CellRange { col: RangeCol; anchor: number; head: number }

/** [작은 행, 큰 행] — 양끝 포함. */
export function rangeBounds(r: CellRange): [number, number] {
  return r.anchor <= r.head ? [r.anchor, r.head] : [r.head, r.anchor]
}

/** 이 칸이 범위 안인가. 열이 다르거나 범위가 없으면 false. */
export function inRange(r: CellRange | null, row: number, col: string): boolean {
  if (!r || r.col !== col) return false
  const [lo, hi] = rangeBounds(r)
  return row >= lo && row <= hi
}

/**
 * 한 열의 칸들을 엑셀 클립보드 글로: 줄마다 한 칸, 비면 빈 칸.
 * 줄바꿈·탭·따옴표가 든 칸은 "로 감싸고 안의 "는 ""로 쓴다. 끝 줄바꿈은 붙이지 않는다.
 */
export function columnClipboard(texts: (string | null | undefined)[]): string {
  return texts
    .map(t => {
      const s = (t ?? '').replace(/\r\n?/g, '\n')
      return /[\n\t"]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
    })
    .join('\n')
}
