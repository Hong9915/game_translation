/** 그리드 열 순서. 글을 편집하는 열은 fix·note, 체크 열(review1·review2·needsCheck·confirmed)은 클릭·Space로 바꾼다. */
export const COLS = ['idx', 'id', 'src', 'en', 'existingKo', 'fix', 'review1', 'review2', 'needsCheck', 'confirmed', 'note'] as const
export type Col = (typeof COLS)[number]
export interface Cell { row: number; col: Col }
export type ArrowKey = 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight'

export function move(cell: Cell, key: ArrowKey, rowCount: number): Cell {
  const ci = COLS.indexOf(cell.col)
  switch (key) {
    case 'ArrowUp': return { ...cell, row: Math.max(0, cell.row - 1) }
    case 'ArrowDown': return { ...cell, row: Math.min(rowCount - 1, cell.row + 1) }
    case 'ArrowLeft': return { ...cell, col: COLS[Math.max(0, ci - 1)] }
    case 'ArrowRight': return { ...cell, col: COLS[Math.min(COLS.length - 1, ci + 1)] }
  }
}

/** Tab/Shift+Tab: 다음(이전) 행의 Fix 칸. 끝이면 null. */
export function nextFixRow(from: number, rowCount: number, dir: 1 | -1): number | null {
  const next = from + dir
  return next < 0 || next >= rowCount ? null : next
}

/**
 * 엑셀처럼 여러 줄을 붙여넣으면 시작 행부터 아래로 한 줄씩 채운다.
 * 아직 안 받은 행(undefined)을 만나면 거기서 멈춘다 — 안 보이는 행을 모르고 덮어쓰지 않도록.
 */
export function distributePaste(text: string, startRow: number, rows: ({ id: number } | undefined)[]): { index: number; text: string }[] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  while (lines.length > 1 && lines[lines.length - 1] === '') lines.pop()
  const out: { index: number; text: string }[] = []
  let i = startRow
  for (const line of lines) {
    if (i >= rows.length || rows[i] === undefined) break
    out.push({ index: i, text: line })
    i++
  }
  return out
}
