import { describe, expect, it } from 'vitest'
import { distributePaste, move, nextFixRow } from './gridNav'

describe('move', () => {
  it('moves across columns and rows and stops at the edges', () => {
    expect(move({ row: 0, col: 'fix' }, 'ArrowRight', 10)).toEqual({ row: 0, col: 'review1' })
    expect(move({ row: 0, col: 'needsCheck' }, 'ArrowRight', 10)).toEqual({ row: 0, col: 'confirmed' })
    // 최종 수정 열은 순서에 늘 있고, 일반 시트에서는 그리드가 숨긴 열을 건너뛴다
    expect(move({ row: 0, col: 'confirmed' }, 'ArrowRight', 10)).toEqual({ row: 0, col: 'finalMark' })
    expect(move({ row: 0, col: 'finalMark' }, 'ArrowRight', 10)).toEqual({ row: 0, col: 'note' })
    expect(move({ row: 0, col: 'note' }, 'ArrowRight', 10)).toEqual({ row: 0, col: 'note' })
    expect(move({ row: 0, col: 'idx' }, 'ArrowLeft', 10)).toEqual({ row: 0, col: 'idx' })
    expect(move({ row: 0, col: 'fix' }, 'ArrowUp', 10)).toEqual({ row: 0, col: 'fix' })
    expect(move({ row: 9, col: 'fix' }, 'ArrowDown', 10)).toEqual({ row: 9, col: 'fix' })
    expect(move({ row: 3, col: 'src' }, 'ArrowDown', 10)).toEqual({ row: 4, col: 'src' })
  })
})

describe('nextFixRow', () => {
  it('returns the next row or null at the end', () => {
    expect(nextFixRow(0, 3, 1)).toBe(1)
    expect(nextFixRow(2, 3, 1)).toBeNull()
    expect(nextFixRow(0, 3, -1)).toBeNull()
  })
})

describe('distributePaste', () => {
  const rows = [
    { id: 1 }, { id: 2 }, { id: 3 }, undefined, { id: 5 },
  ]
  it('fills downward from the start row and drops a trailing newline', () => {
    expect(distributePaste('가\n나\n', 0, rows)).toEqual([{ index: 0, text: '가' }, { index: 1, text: '나' }])
  })
  it('stops at a row that is not loaded yet', () => {
    expect(distributePaste('가\r\n나\r\n다', 2, rows)).toEqual([{ index: 2, text: '가' }])
  })
  it('keeps a single line on the start row even if blank lines follow', () => {
    expect(distributePaste('가', 4, rows)).toEqual([{ index: 4, text: '가' }])
  })
})
