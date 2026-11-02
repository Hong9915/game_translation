import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkspaceRow } from '../../api/types'
import { WorkspaceGrid } from './WorkspaceGrid'

/** 행 숨기기·행 높이·열 너비(2026-10-12). 화면에만 걸리고 이 브라우저에 기억한다. */
const row = (i: number, over: Partial<WorkspaceRow> = {}): WorkspaceRow => ({
  id: 100 + i, rowNo: i + 2, pairIndex: 0, indexLabel: `UI!${i + 2}`, keyText: `UI_${i}`, src: `源${i}`, en: `Chaos${i}`, existingKo: null, fix: `한${i}`,
  changed: false, termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, needsCheck: false, confirmed: false, finalReview: false, finalMark: false, note: null, lastEditor: null, lastEditedAt: null, history: [], dupCount: 0, ...over,
})
function renderGrid(rows: (WorkspaceRow | undefined)[], over: Partial<ComponentProps<typeof WorkspaceGrid>> = {}) {
  const props = { rows, ratio: 1, busy: new Set<number>(), highlighted: new Set<number>(), failedText: new Map<number, { text: string; base: string }>(), onSave: vi.fn(), onMarks: vi.fn(), onBulkMarks: vi.fn(), viewCount: rows.length, onVisibleRange: vi.fn(), onFilterDuplicates: vi.fn(), onAddToGlossary: vi.fn(), onTranslate: vi.fn(), onShorten: vi.fn(), onJosaApply: vi.fn(), onJosaIgnore: vi.fn(), onHistory: vi.fn(() => Promise.resolve([])), translating: new Set<number>(), ...over }
  const utils = render(<WorkspaceGrid {...props} />)
  return { ...utils, props }
}
const rowsOf = (n: number) => Array.from({ length: n }, (_, i) => row(i))

afterEach(() => localStorage.clear())

describe('열 숨기기', () => {
  it('머리글 우클릭 → 열 숨기기면 그 열이 사라지고, 모두 보이기로 돌아온다(새로 열어도 기억)', async () => {
    const first = renderGrid(rowsOf(2))
    fireEvent.contextMenu(screen.getByRole('columnheader', { name: '영어' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '열 숨기기' }))
    expect(screen.queryByRole('columnheader', { name: '영어' })).not.toBeInTheDocument()
    expect(screen.queryByText('Chaos0')).not.toBeInTheDocument()
    expect(document.querySelector('col[data-col="en"]')).toBeNull()
    first.unmount()

    renderGrid(rowsOf(2))
    expect(screen.queryByRole('columnheader', { name: '영어' })).not.toBeInTheDocument()
    const showAll = screen.getByRole('button', { name: '숨긴 열 1개 · 모두 보이기' })
    expect(showAll).toHaveAttribute('title', expect.stringContaining('영어'))
    await userEvent.click(showAll)
    expect(screen.getByRole('columnheader', { name: '영어' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /모두 보이기/ })).not.toBeInTheDocument()
  })

  it('게임 화면에서 ID 열을 숨겨도 파일 / 시트 열은 # 바로 뒤에 머리글과 칸이 맞게 남는다', async () => {
    renderGrid(rowsOf(1), { placeOf: () => 'A.xlsx / UI' })
    fireEvent.contextMenu(screen.getByRole('columnheader', { name: 'ID' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '열 숨기기' }))
    expect(screen.getAllByRole('columnheader').slice(0, 3).map((h) => h.getAttribute('aria-label'))).toEqual(['#', '파일 / 시트', '간체 원문'])
    const cells = screen.getAllByRole('row')[1].querySelectorAll('td')
    expect(cells[1].textContent).toBe('A.xlsx / UI')
    expect(cells[2].textContent).toContain('源0')
  })

  it('# 열과 Fix 열 머리글에는 열 숨기기가 없다', () => {
    renderGrid(rowsOf(1))
    fireEvent.contextMenu(screen.getByRole('columnheader', { name: '수정 한국어 (Fix)' }))
    expect(screen.queryByRole('menuitem', { name: '열 숨기기' })).not.toBeInTheDocument()
    fireEvent.contextMenu(screen.getByRole('columnheader', { name: '#' }))
    expect(screen.queryByRole('menuitem', { name: '열 숨기기' })).not.toBeInTheDocument()
  })

  it('좌우 화살표는 숨긴 열을 건너뛴다', async () => {
    renderGrid(rowsOf(1))
    for (const name of ['영어', '기존 한국어']) {
      fireEvent.contextMenu(screen.getByRole('columnheader', { name }))
      await userEvent.click(screen.getByRole('menuitem', { name: '열 숨기기' }))
    }
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 Fix' }))
    const grid = screen.getByRole('grid')
    const region = () => screen.getByRole('region', { name: '선택한 칸 내용' })
    fireEvent.keyDown(grid, { key: 'ArrowLeft' })
    expect(region()).toHaveTextContent('2행 · 간체 원문')
    fireEvent.keyDown(grid, { key: 'ArrowRight' })
    expect(region()).toHaveTextContent('2행 · 수정 한국어 (Fix)')
  })
})

describe('행 높이', () => {
  it('아무 행의 # 칸 아래 경계를 끌면 모든 행이 같이 높아지고, 더블클릭하면 모두 원래대로', () => {
    renderGrid(rowsOf(3))
    const handle = screen.getByRole('separator', { name: '2행 높이 조절' })
    fireEvent.mouseDown(handle, { clientY: 100 })
    fireEvent.mouseMove(window, { clientY: 160 })
    fireEvent.mouseUp(window, { clientY: 160 })
    const heightOf = (rowNo: number) => screen.getByRole('separator', { name: `${rowNo}행 높이 조절` }).closest('tr')!.style.height
    expect([2, 3, 4].map(heightOf)).toEqual(['86px', '86px', '86px'])
    // 높인 행은 글이 여러 줄로 감긴다
    expect(screen.getByRole('gridcell', { name: '4행 Fix' }).className).toContain('whitespace-normal')

    fireEvent.doubleClick(screen.getByRole('separator', { name: '3행 높이 조절' }))
    expect([2, 3, 4].map(heightOf)).toEqual(['26px', '26px', '26px'])
  })
})

describe('열 너비', () => {
  it('머리글 오른쪽 경계를 끌면 열이 넓어지고 기억되며, 더블클릭하면 원래 너비로', () => {
    const first = renderGrid(rowsOf(1))
    const handle = screen.getByRole('separator', { name: '수정 한국어 (Fix) 너비 조절' })
    fireEvent.mouseDown(handle, { clientX: 500 })
    fireEvent.mouseMove(window, { clientX: 600 })
    fireEvent.mouseUp(window, { clientX: 600 })
    const col = () => document.querySelector('col[data-col="fix"]') as HTMLElement
    expect(col().style.width).toBe('388px')
    first.unmount()

    renderGrid(rowsOf(1))
    expect(col().style.width).toBe('388px')
    fireEvent.doubleClick(screen.getByRole('separator', { name: '수정 한국어 (Fix) 너비 조절' }))
    expect(col().style.width).toBe('288px')
  })

  it('체크 열 경계를 더블클릭해도 일괄 체크 창은 열리지 않는다', () => {
    renderGrid(rowsOf(1))
    fireEvent.doubleClick(screen.getByRole('separator', { name: 'A 너비 조절' }))
    expect(screen.queryByRole('button', { name: '모두 체크' })).not.toBeInTheDocument()
  })
})
