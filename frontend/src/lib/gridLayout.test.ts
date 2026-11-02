import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { indexAt, loadRowHeight, rowOffsets, useGridLayout } from './gridLayout'

afterEach(() => localStorage.clear())

describe('rowOffsets / indexAt', () => {
  it('누적 높이로 행 위치를 잡고, 스크롤 위치에서 행 번호를 찾는다', () => {
    const offsets = rowOffsets([26, 4, 80, 26])
    expect(offsets).toEqual([0, 26, 30, 110, 136])
    expect(indexAt(offsets, 0)).toBe(0)
    expect(indexAt(offsets, 25)).toBe(0)
    expect(indexAt(offsets, 26)).toBe(1)
    expect(indexAt(offsets, 29)).toBe(1)
    expect(indexAt(offsets, 100)).toBe(2)
    expect(indexAt(offsets, 135)).toBe(3)
    // 끝을 넘으면 마지막 행
    expect(indexAt(offsets, 999)).toBe(3)
    expect(indexAt(rowOffsets([]), 10)).toBe(0)
  })
})

describe('useGridLayout', () => {
  it('숨긴 열·행 높이·열 너비를 이 브라우저에 기억한다', () => {
    const { result } = renderHook(() => useGridLayout())
    act(() => { result.current.hideCol('en'); result.current.setRowHeight(90); result.current.setWidth('fix', 400) })
    const again = renderHook(() => useGridLayout()).result.current
    expect(again.hiddenCols.has('en')).toBe(true)
    expect(again.rowHeight).toBe(90)
    expect(loadRowHeight()).toBe(90)
    expect(again.widths.fix).toBe(400)
    expect(again.widths.src).toBe(224)

    act(() => { result.current.showCol('en'); result.current.resetRowHeight(); result.current.resetWidth('fix') })
    const reset = renderHook(() => useGridLayout()).result.current
    expect(reset.hiddenCols.size).toBe(0)
    expect(reset.rowHeight).toBe(26)
    expect(reset.widths.fix).toBe(288)
  })

  it('모두 보이기는 숨긴 열을 다 풀고, # 열과 Fix 열은 숨기지 않는다', () => {
    const { result } = renderHook(() => useGridLayout())
    act(() => { result.current.hideCol('en'); result.current.hideCol('note'); result.current.hideCol('idx'); result.current.hideCol('fix') })
    expect([...result.current.hiddenCols].sort()).toEqual(['en', 'note'])
    act(() => result.current.showAllCols())
    expect(result.current.hiddenCols.size).toBe(0)
  })

  it('저장된 값이 깨져 있으면 기본값으로 시작한다', () => {
    localStorage.setItem('gametrans.gridHiddenCols', '{oops')
    localStorage.setItem('gametrans.gridRowHeight', '"tall"')
    localStorage.setItem('gametrans.gridColWidths', '{"fix":"wide","src":10}')
    const { result } = renderHook(() => useGridLayout())
    expect(result.current.hiddenCols.size).toBe(0)
    expect(result.current.rowHeight).toBe(26)
    expect(result.current.widths.fix).toBe(288)
    // 너무 좁은 값은 최소 너비로
    expect(result.current.widths.src).toBe(40)
  })
})
