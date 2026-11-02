import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { WorkspaceRow } from '../../api/types'
import { WorkspaceGrid } from './WorkspaceGrid'

const row = (i: number, over: Partial<WorkspaceRow> = {}): WorkspaceRow => ({
  id: 100 + i, rowNo: i + 2, pairIndex: 0, indexLabel: `UI!${i + 2}`, keyText: `UI_${i}`, src: '混沌圣物', en: 'Chaos Relic', existingKo: '혼돈의 성물', fix: '혼돈의 성물',
  changed: false, termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, needsCheck: false, confirmed: false, finalReview: false, finalMark: false, note: null, lastEditor: null, lastEditedAt: null, history: [], dupCount: 0, ...over,
})
const noop = () => {}
function renderGrid(rows: (WorkspaceRow | undefined)[], over: Partial<ComponentProps<typeof WorkspaceGrid>> = {}) {
  const props = { rows, ratio: 1, busy: new Set<number>(), highlighted: new Set<number>(), failedText: new Map<number, { text: string; base: string }>(), onSave: vi.fn(), onMarks: vi.fn(), onBulkMarks: vi.fn(), viewCount: rows.length, onVisibleRange: vi.fn(), onFilterDuplicates: vi.fn(), onAddToGlossary: vi.fn(), onTranslate: vi.fn(), onShorten: vi.fn(), onJosaApply: vi.fn(), onJosaIgnore: vi.fn(), onHistory: vi.fn(() => Promise.resolve([])), translating: new Set<number>(), ...over }
  const utils = render(<WorkspaceGrid {...props} />)
  return { ...utils, props }
}

describe('WorkspaceGrid', () => {
  it('shows a josa badge that opens the hint popover', async () => {
    const r = row(0, { fix: '아군의 생명를 회복', josaHints: [{ at: 4, from: '생명를', to: '생명을', kind: 'HANGUL' }] })
    const { props } = renderGrid([r])
    await userEvent.click(screen.getByRole('button', { name: '조사 제안 1개' }))
    expect(screen.getByRole('dialog', { name: '조사 제안' })).toHaveTextContent('생명를 → 생명을')
    await userEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(props.onJosaApply).toHaveBeenCalledWith(r)
  })

  it('shows which glossary terms are missing when hovering the term-miss dot', () => {
    renderGrid([row(0, { fix: '성물', termMiss: ['混沌圣物→혼돈의 성물', '圣物→성물'] })])
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    const dot = screen.getByLabelText('용어 불일치: 混沌圣物→혼돈의 성물, 圣物→성물')
    fireEvent.mouseEnter(dot)
    const tip = screen.getByRole('tooltip')
    expect(tip).toHaveTextContent('混沌圣物 → 혼돈의 성물')
    expect(tip).toHaveTextContent('圣物 → 성물')
    fireEvent.mouseLeave(dot)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('renders the columns, marks changed fix cells and placeholder rows', () => {
    renderGrid([row(0), row(1, { fix: '성물', changed: true, termMiss: ['混沌圣物→성물'] }), undefined])
    expect(screen.getByRole('columnheader', { name: '수정 한국어 (Fix)' })).toBeInTheDocument()
    expect(screen.getByText('UI_0')).toBeInTheDocument()
    const changed = screen.getByRole('gridcell', { name: '3행 Fix' })
    expect(changed).toHaveTextContent('성물')
    expect(changed.className).toContain('fff7d6')
    expect(screen.getByLabelText('용어 불일치: 混沌圣物→성물')).toBeInTheDocument()
    expect(screen.getAllByText('…').length).toBeGreaterThan(0)
    // 아직 안 받은 행도 ID 열이 로드된 행과 같이 sticky로 고정돼야 한다(가로 스크롤 중 계속 보이도록)
    const gridRows = screen.getAllByRole('row')
    const placeholderCells = within(gridRows[gridRows.length - 1]).getAllByRole('gridcell')
    expect(placeholderCells[1].className).toContain('sticky')
    expect(placeholderCells[1].className).toContain('left-12')
  })

  it('reports the visible range on mount', () => {
    const { props } = renderGrid(Array.from({ length: 2000 }, () => undefined))
    expect(props.onVisibleRange).toHaveBeenCalledWith(0, expect.any(Number))
    expect((props.onVisibleRange as ReturnType<typeof vi.fn>).mock.calls[0][1]).toBeLessThan(200)
  })

  it('navigates with arrows, edits with Enter and saves on Enter moving down', async () => {
    const { props } = renderGrid([row(0), row(1)])
    const grid = screen.getByRole('grid')
    grid.focus()
    fireEvent.keyDown(grid, { key: 'ArrowDown' })   // 아무것도 안 골랐을 때 첫 키는 1행 Fix를 고른다
    fireEvent.keyDown(grid, { key: 'ArrowRight' })
    expect(screen.getByRole('gridcell', { name: '2행 A' }).className).toContain('outline-[#107c41]')   // 1차 칸으로 이동했다
    fireEvent.keyDown(grid, { key: 'ArrowLeft' })
    fireEvent.keyDown(grid, { key: 'Enter' })
    const box = screen.getByRole('textbox', { name: '2행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '성물')
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), '성물')
    // 아래 행 fix가 선택됐다: Enter로 바로 그 행 편집이 열린다
    fireEvent.keyDown(grid, { key: 'Enter' })
    expect(screen.getByRole('textbox', { name: '3행 Fix' })).toBeInTheDocument()
  })

  it('does not save when the text is unchanged or edit is cancelled', async () => {
    const { props } = renderGrid([row(0)])
    const grid = screen.getByRole('grid')
    fireEvent.keyDown(grid, { key: 'ArrowDown' })   // 첫 키 = 1행 Fix 선택
    fireEvent.keyDown(grid, { key: 'F2' })
    fireEvent.keyDown(screen.getByRole('textbox', { name: '2행 Fix' }), { key: 'Enter' })
    expect(props.onSave).not.toHaveBeenCalled()
    fireEvent.keyDown(grid, { key: 'Enter' })
    const box = screen.getByRole('textbox', { name: '2행 Fix' })
    await userEvent.type(box, '!!')
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(props.onSave).not.toHaveBeenCalled()
    expect(screen.queryByRole('textbox', { name: '2행 Fix' })).not.toBeInTheDocument()
  })

  it('has 1차·2차·확인 필요·확정·비고 columns instead of 완료·담당', () => {
    renderGrid([row(0)])
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).toEqual(['#', 'ID', '간체 원문', '영어', '기존 한국어', '수정 한국어 (Fix)', 'A', 'B', '확인 필요', '확정', '비고'])
  })

  it('toggles each mark by clicking its cell', async () => {
    const r = row(0, { review1: true, review2: true })
    const { props } = renderGrid([r])
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 A' }))
    expect(props.onMarks).toHaveBeenLastCalledWith(expect.objectContaining({ id: 100 }), { review1: false })
    await userEvent.click(screen.getByRole('checkbox', { name: '2행 B' }))
    expect(props.onMarks).toHaveBeenLastCalledWith(expect.objectContaining({ id: 100 }), { review2: false })
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 확인 필요' }))
    expect(props.onMarks).toHaveBeenLastCalledWith(expect.objectContaining({ id: 100 }), { needsCheck: true })
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 확정' }))
    expect(props.onMarks).toHaveBeenLastCalledWith(expect.objectContaining({ id: 100 }), { confirmed: true })
    expect(screen.getByRole('checkbox', { name: '2행 A' })).toBeChecked()
  })

  it('Space toggles the selected mark and ⌘Enter toggles 1차 from any column', () => {
    const { props } = renderGrid([row(0)])
    const grid = screen.getByRole('grid')
    fireEvent.keyDown(grid, { key: 'ArrowDown' })              // 1행 Fix
    fireEvent.keyDown(grid, { key: 'Enter', metaKey: true })
    expect(props.onMarks).toHaveBeenLastCalledWith(expect.objectContaining({ id: 100 }), { review1: true })
    fireEvent.keyDown(grid, { key: 'ArrowRight' })             // 1차
    fireEvent.keyDown(grid, { key: 'ArrowRight' })             // 2차
    fireEvent.keyDown(grid, { key: ' ' })
    expect(props.onMarks).toHaveBeenLastCalledWith(expect.objectContaining({ id: 100 }), { review2: true })
    expect(screen.queryByRole('textbox', { name: '2행 Fix' })).not.toBeInTheDocument()
  })

  it('double-clicking a mark header opens the bulk popover for the rows in view', async () => {
    const onBulkMarks = vi.fn()
    renderGrid([row(0), row(1)], { onBulkMarks, viewCount: 250 })
    fireEvent.doubleClick(screen.getByRole('columnheader', { name: 'A' }))
    expect(screen.getByRole('dialog', { name: 'A 일괄 체크' })).toHaveTextContent('이 보기의 250행')
    await userEvent.click(screen.getByRole('button', { name: '모두 체크' }))
    expect(onBulkMarks).toHaveBeenCalledWith('review1', true)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.doubleClick(screen.getByRole('columnheader', { name: '확인 필요' }))
    await userEvent.click(screen.getByRole('button', { name: '모두 해제' }))
    expect(onBulkMarks).toHaveBeenLastCalledWith('needsCheck', false)
    fireEvent.doubleClick(screen.getByRole('columnheader', { name: '비고' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('edits the note like Fix: double-click, type, Enter saves; Escape cancels', async () => {
    const r = row(0, { note: '옛 메모' })
    const { props } = renderGrid([r])
    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '2행 비고' }))
    const box = screen.getByRole('textbox', { name: '2행 비고' })
    expect(box).toHaveValue('옛 메모')
    await userEvent.clear(box)
    await userEvent.type(box, '새 메모')
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(props.onMarks).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), { note: '새 메모' })
    expect(props.onSave).not.toHaveBeenCalled()

    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '2행 비고' }))
    await userEvent.type(screen.getByRole('textbox', { name: '2행 비고' }), 'x')
    fireEvent.keyDown(screen.getByRole('textbox', { name: '2행 비고' }), { key: 'Escape' })
    expect(props.onMarks).toHaveBeenCalledTimes(1)
  })

  it('keeps a note being edited when the row changes underneath (another reviewer checks it)', async () => {
    const { rerender, props } = renderGrid([row(0)])
    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '2행 비고' }))
    await userEvent.type(screen.getByRole('textbox', { name: '2행 비고' }), '쓰는 중')
    rerender(<WorkspaceGrid {...props} rows={[row(0, { review1: true, review2: true })]} />)
    expect(screen.getByRole('textbox', { name: '2행 비고' })).toHaveValue('쓰는 중')
    expect(screen.getByRole('checkbox', { name: '2행 A' })).toBeChecked()
  })

  it('saves an edit to the row it started on even if the list shifts underneath, and drops it if that row is gone', async () => {
    const { rerender, props } = renderGrid([row(0), row(1)])
    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '2행 비고' }))
    await userEvent.type(screen.getByRole('textbox', { name: '2행 비고' }), '메모')
    // 목록이 다시 읽혀 앞에 다른 행이 끼어들었다(필터 결과가 바뀜)
    rerender(<WorkspaceGrid {...props} rows={[row(5), row(0), row(1)]} />)
    fireEvent.keyDown(screen.getByRole('textbox', { name: '2행 비고' }), { key: 'Enter' })
    expect(props.onMarks).toHaveBeenCalledTimes(1)
    expect(props.onMarks).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), { note: '메모' })

    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    await userEvent.type(screen.getByRole('textbox', { name: '3행 Fix' }), '!')
    // 편집하던 행이 목록에서 빠졌다 — 다른 행에 저장하면 안 된다
    rerender(<WorkspaceGrid {...props} rows={[row(5), row(0)]} />)
    expect(props.onSave).not.toHaveBeenCalled()
    expect(screen.queryByRole('textbox', { name: '3행 Fix' })).not.toBeInTheDocument()
  })

  it('edits the note from the bar above the table', async () => {
    const { props } = renderGrid([row(0)])
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 비고' }))
    const bar = screen.getByRole('textbox', { name: '선택한 칸 편집' })
    await userEvent.type(bar, '입력줄 메모{Enter}')
    expect(props.onMarks).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), { note: '입력줄 메모' })
  })

  it('distributes a multi-line paste downward from the selected fix cell, also over checked rows', () => {
    const { props } = renderGrid([row(0), row(1, { review1: true, review2: true }), row(2)])
    const grid = screen.getByRole('grid')
    fireEvent.keyDown(grid, { key: 'ArrowDown' })   // 첫 키 = 1행 Fix 선택
    fireEvent.paste(grid, { clipboardData: { getData: () => '성물\n혼돈의 성물\n' } })
    expect(props.onSave).toHaveBeenCalledTimes(1)   // 2행은 '성물'로 저장, 3행(체크됨)은 같은 값이라 안 보냄
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), '성물')
  })

  it('saves on a real blur without dropping the next edit closed the same way (regression: finishingRef)', async () => {
    const { props } = renderGrid([row(0), row(1)])
    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '2행 Fix' }))
    const boxA = screen.getByRole('textbox', { name: '2행 Fix' })
    await userEvent.clear(boxA)
    await userEvent.type(boxA, '성물A')
    fireEvent.blur(boxA)
    expect(props.onSave).toHaveBeenCalledTimes(1)
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), '성물A')

    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const boxB = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(boxB)
    await userEvent.type(boxB, '성물B')
    fireEvent.blur(boxB)
    expect(props.onSave).toHaveBeenCalledTimes(2)
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: 101 }), '성물B')
  })

  it('shows saving state instead of the text while busy, and a length flag otherwise', () => {
    const { rerender } = renderGrid([row(0, { fix: '혼돈의 성물이라는 아주 긴 번역문' })], { busy: new Set([100]) })
    expect(screen.getByRole('gridcell', { name: '2행 Fix' })).toHaveTextContent('저장 중…')
    rerender(<WorkspaceGrid rows={[row(0, { fix: '혼돈의 성물이라는 아주 긴 번역문' })]} ratio={1} busy={new Set()} highlighted={new Set()} failedText={new Map()} onSave={noop} onMarks={noop} onBulkMarks={noop} viewCount={1} onVisibleRange={noop} onFilterDuplicates={noop} onAddToGlossary={noop} onTranslate={noop} onShorten={vi.fn()} onJosaApply={noop} onJosaIgnore={noop} onHistory={vi.fn()} translating={new Set()} />)
    expect(screen.getByText('길이')).toBeInTheDocument()
  })

  it('shows failed-save text with a red border instead of the saved fix', () => {
    renderGrid([row(0, { fix: '혼돈의 성물' })], { failedText: new Map([[100, { text: '내가 입력한 값', base: '혼돈의 성물' }]]) })
    const cell = screen.getByRole('gridcell', { name: '2행 Fix' })
    expect(cell).toHaveTextContent('내가 입력한 값')
    expect(cell).not.toHaveTextContent('혼돈의 성물')
    expect(cell.className).toContain('border-red-500')
  })

  it('원문이 여러 곳에 있으면 ×N 칩을 보여 준다', () => {
    renderGrid([row(0, { dupCount: 3 })])
    expect(screen.getByText('×3')).toBeInTheDocument()
  })

  it('원문이 한 곳뿐이면 칩이 없다', () => {
    renderGrid([row(0, { dupCount: 1 })])
    expect(screen.queryByText('×1')).not.toBeInTheDocument()
  })

  it('칩을 누르면 같은 행 / 포함된 전체 행 중 골라 그 원문으로 모아 본다', async () => {
    const { props } = renderGrid([row(0, { dupCount: 5, src: '混沌圣物' })])
    await userEvent.click(screen.getByText('×5'))
    await userEvent.click(screen.getByRole('menuitem', { name: '같은 행 보기' }))
    expect(props.onFilterDuplicates).toHaveBeenLastCalledWith('混沌圣物', 'exact')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    await userEvent.click(screen.getByText('×5'))
    await userEvent.click(screen.getByRole('menuitem', { name: '포함된 전체 행 보기' }))
    expect(props.onFilterDuplicates).toHaveBeenLastCalledWith('混沌圣物', 'contains')
  })

  it('원문 셀 우클릭에서 단어장에 추가를 부른다', async () => {
    const onAdd = vi.fn()
    const r = row(0)
    renderGrid([r], { onAddToGlossary: onAdd })
    fireEvent.contextMenu(screen.getByText(r.src))
    await userEvent.click(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' }))
    expect(onAdd).toHaveBeenCalledWith('SRC', r.src, true)
  })

  it('Fix 셀 우클릭은 한국어 종류로 부른다', async () => {
    const onAdd = vi.fn()
    const r = row(0, { fix: '고친 한국어' })
    renderGrid([r], { onAddToGlossary: onAdd })
    fireEvent.contextMenu(screen.getByText(r.fix!))
    await userEvent.click(screen.getByRole('menuitem', { name: '단어장에 추가 (한국어)' }))
    expect(onAdd).toHaveBeenCalledWith('KO', r.fix, true)
  })

  it('원문 셀 안에서 일부만 선택했으면 그 부분만 넘긴다', async () => {
    const onAdd = vi.fn()
    const r = row(0)
    renderGrid([r], { onAddToGlossary: onAdd })
    const cell = screen.getByText(r.src)
    const textNode = cell.firstChild!
    const range = document.createRange()
    range.setStart(textNode, 1)
    range.setEnd(textNode, 3)
    const sel = window.getSelection()!
    sel.removeAllRanges()
    sel.addRange(range)
    fireEvent.contextMenu(cell)
    await userEvent.click(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' }))
    expect(onAdd).toHaveBeenCalledWith('SRC', r.src.slice(1, 3), false)
  })

  it('메뉴가 뜬 뒤 Esc를 누르면 닫힌다', () => {
    const r = row(0)
    renderGrid([r])
    fireEvent.contextMenu(screen.getByText(r.src))
    expect(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' })).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menuitem')).not.toBeInTheDocument()
  })

  it('×N 칩을 우클릭해도 원문 셀 메뉴가 정상적으로 뜬다', async () => {
    const onAdd = vi.fn()
    const r = row(0, { dupCount: 3, src: '混沌圣物' })
    renderGrid([r], { onAddToGlossary: onAdd })
    fireEvent.contextMenu(screen.getByText('×3'))
    await userEvent.click(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' }))
    expect(onAdd).toHaveBeenCalledWith('SRC', r.src, true)
  })

  it('메뉴가 열려 있을 때 Esc는 메뉴만 닫고, 다른 칸에서 편집 중인 내용은 건드리지 않는다 (다음 Esc는 그 편집을 취소한다)', async () => {
    const r0 = row(0)
    const r1 = row(1, { src: '另一个原文' })
    renderGrid([r0, r1])
    fireEvent.doubleClick(screen.getByRole('gridcell', { name: '2행 Fix' }))
    const box = screen.getByRole('textbox', { name: '2행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '입력 중인 값')

    fireEvent.contextMenu(screen.getByText(r1.src))
    expect(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' })).toBeInTheDocument()

    // Esc가 편집 중이던 textarea에서 온다(포커스가 거기 있다) — 메뉴만 닫혀야지, 이 편집을 취소하면 안 된다.
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('menuitem')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '2행 Fix' })).toHaveValue('입력 중인 값')

    // 메뉴가 없을 때 Esc는 평소대로 편집을 취소한다.
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('textbox', { name: '2행 Fix' })).not.toBeInTheDocument()
  })

  it('원문 셀 우클릭 → DeepL 번역을 누르면 지시 창 없이 바로 그 행으로 onTranslate를 부른다', async () => {
    const onTranslate = vi.fn()
    const r = row(0)
    renderGrid([r], { onTranslate })
    fireEvent.contextMenu(screen.getByText(r.src))
    await userEvent.click(screen.getByRole('menuitem', { name: 'DeepL 번역' }))
    expect(onTranslate).toHaveBeenCalledWith(expect.objectContaining({ id: r.id }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('체크된 행에서도 DeepL 번역 메뉴가 켜져 있다', () => {
    const r = row(0, { review1: true, review2: true })
    renderGrid([r], { onTranslate: vi.fn() })
    fireEvent.contextMenu(screen.getByText(r.src))
    expect(screen.getByRole('menuitem', { name: 'DeepL 번역' })).toBeEnabled()
  })

  it('기존 한국어 셀 우클릭 → 짧게 줄이기 → 후보를 고르면 그 행에 onSave로 저장한다', async () => {
    const r = row(0, { existingKo: '적에게 큰 피해를 입힙니다', fix: null })
    const onShorten = vi.fn().mockResolvedValue({ ko: r.existingKo, len: 11, target: 8, candidates: [{ ko: '적에게 큰 피해', len: 6 }] })
    const { props } = renderGrid([r], { onShorten })
    fireEvent.contextMenu(screen.getByText(r.existingKo!))
    await userEvent.click(screen.getByRole('menuitem', { name: '짧게 줄이기' }))
    expect(onShorten).toHaveBeenCalledWith(expect.objectContaining({ id: r.id }), undefined)
    await userEvent.click(await screen.findByRole('button', { name: /^적에게 큰 피해\s*6자$/ }))
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: r.id }), '적에게 큰 피해')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('Fix 셀 메뉴에도 짧게 줄이기가 있고, 원문 셀 메뉴에는 없으며, 체크된 행에서도 켜져 있다', () => {
    const r = row(0, { fix: '고친 한국어' })
    const { unmount } = renderGrid([r])
    fireEvent.contextMenu(screen.getByText(r.fix!))
    expect(screen.getByRole('menuitem', { name: '짧게 줄이기' })).toBeEnabled()
    fireEvent.keyDown(document, { key: 'Escape' })
    fireEvent.contextMenu(screen.getByText(r.src))
    expect(screen.queryByRole('menuitem', { name: '짧게 줄이기' })).not.toBeInTheDocument()
    unmount()

    const d = row(0, { fix: '고친 한국어', review1: true, review2: true })
    renderGrid([d])
    fireEvent.contextMenu(screen.getByText(d.fix!))
    expect(screen.getByRole('menuitem', { name: '짧게 줄이기' })).toBeEnabled()
  })

  it('Fix 셀 우클릭 메뉴에는 DeepL 번역이 없다', () => {
    const r = row(0, { fix: '고친 한국어' })
    renderGrid([r], { onTranslate: vi.fn() })
    fireEvent.contextMenu(screen.getByText(r.fix!))
    expect(screen.queryByRole('menuitem', { name: 'DeepL 번역' })).not.toBeInTheDocument()
  })

  it('번역 중인 행의 Fix는 "번역 중…"을 보이고 편집이 열리지 않는다', () => {
    const r = row(0)
    renderGrid([r], { translating: new Set([r.id]) })
    const cell = screen.getByRole('gridcell', { name: '2행 Fix' })
    expect(cell).toHaveTextContent('번역 중…')
    fireEvent.doubleClick(cell)
    expect(screen.queryByRole('textbox', { name: '2행 Fix' })).not.toBeInTheDocument()
  })

  it('shows the whole text of the selected cell in the bar above the grid, live while editing', async () => {
    const long = '이벤트 기간 동안 <color=#78fa5a>한정 음식</color>을 섭취하면 푸짐한 보상을 받을 수 있습니다. 끝까지 보이는지 확인'
    renderGrid([row(0, { fix: long, src: '活动期间烹饪<color=#78fa5a>限定</color>' })])
    const bar = screen.getByRole('region', { name: '선택한 칸 내용' })
    expect(bar).toHaveTextContent('칸을 고르면 전체 내용이 여기 보입니다')
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 Fix' }))
    expect(bar).toHaveTextContent('2행 · 수정 한국어 (Fix)')
    expect(bar).toHaveTextContent(long)
    fireEvent.mouseDown(screen.getAllByRole('gridcell')[2], { button: 0 })
    expect(bar).toHaveTextContent('2행 · 간체 원문')
    expect(within(bar).getByRole('textbox', { name: '선택한 칸 내용 보기' })).toHaveValue('活动期间烹饪<color=#78fa5a>限定</color>')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '2행 Fix' }))
    await userEvent.type(screen.getByRole('textbox', { name: '2행 Fix' }), '!')
    expect(within(bar).getByRole('textbox', { name: '선택한 칸 편집' })).toHaveValue(long + '!')
  })

  it('edits the selected Fix cell right in the bar above the grid, like an Excel formula bar', async () => {
    const { props } = renderGrid([row(0), row(1)])
    const bar = screen.getByRole('region', { name: '선택한 칸 내용' })
    // Fix가 아닌 칸은 보기만 한다
    fireEvent.mouseDown(screen.getAllByRole('gridcell')[2], { button: 0 })
    expect(within(bar).queryByRole('textbox', { name: '선택한 칸 편집' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 Fix' }))
    const input = within(bar).getByRole('textbox', { name: '선택한 칸 편집' })
    expect(input).toHaveValue('혼돈의 성물')
    await userEvent.click(input)
    // 칸 안에 편집 상자를 따로 열지 않는다 — 포커스는 위 칸에 머문다
    expect(screen.queryByRole('textbox', { name: '2행 Fix' })).not.toBeInTheDocument()
    expect(input).toHaveFocus()
    await userEvent.clear(input)
    await userEvent.type(input, '성물')
    expect(screen.getByRole('gridcell', { name: '2행 Fix' })).toHaveTextContent('성물')   // 칸도 따라 보인다
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), '성물')
    expect(screen.getByRole('gridcell', { name: '3행 Fix' }).className).toContain('outline-[#107c41]')
  })

  it('moves an in-cell edit up to the bar without saving halfway', async () => {
    const { props } = renderGrid([row(0)])
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '2행 Fix' }))
    await userEvent.type(screen.getByRole('textbox', { name: '2행 Fix' }), '!')
    const input = screen.getByRole('textbox', { name: '선택한 칸 편집' })
    await userEvent.click(input)
    expect(props.onSave).not.toHaveBeenCalled()
    expect(input).toHaveValue('혼돈의 성물!')
    await userEvent.type(input, '?')
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(props.onSave).not.toHaveBeenCalled()
    expect(input).toHaveValue('혼돈의 성물')
  })

  it('# 칸을 마지막으로 고친 사람의 색으로 칠하고, 이름을 툴팁으로 보인다', () => {
    const editor = { id: 1, name: '지민', color: '#1f4b99' }
    renderGrid([row(0, { lastEditor: editor, lastEditedAt: '2026-10-08T04:20:00Z' }), row(1)])
    const edited = screen.getByRole('gridcell', { name: '2' })
    expect(edited).toHaveAttribute('title', expect.stringContaining('마지막 수정: 지민'))
    expect(edited.style.boxShadow).toBe('inset 3px 0 0 #1f4b99')
    const untouched = screen.getByRole('gridcell', { name: '3' })
    expect(untouched).not.toHaveAttribute('title')
    expect(untouched.style.boxShadow).toBe('')
  })

  it('# 칸 우클릭은 변경 내역 메뉴만 열고, 고르면 그 행의 내역 창을 연다', async () => {
    const r = row(0)
    const onHistory = vi.fn(() => Promise.resolve([{ version: 1, author: 'EXISTING' as const, text: '혼돈의 성물', createdAt: new Date().toISOString(), reviewer: null, activityKind: null }]))
    renderGrid([r], { onHistory })
    fireEvent.contextMenu(screen.getByRole('gridcell', { name: '2' }))
    expect(screen.queryByRole('menuitem', { name: /단어장에 추가/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('menuitem', { name: '변경 내역 보기' }))
    expect(onHistory).toHaveBeenCalledWith(r)
    expect(await screen.findByRole('dialog', { name: '2행 변경 내역' })).toHaveTextContent('기존 번역')
  })

  it('변경 내역 창에서 앞 판으로 되돌리면 그 글로 저장한다', async () => {
    const r = row(0, { fix: '혼돈의 유물' })
    const at = new Date().toISOString()
    const onHistory = vi.fn(() => Promise.resolve([
      { version: 2, author: 'HUMAN' as const, text: '혼돈의 유물', createdAt: at, reviewer: null, activityKind: null },
      { version: 1, author: 'EXISTING' as const, text: '혼돈의 성물', createdAt: at, reviewer: null, activityKind: null },
    ]))
    const onSave = vi.fn()
    renderGrid([r], { onHistory, onSave })
    fireEvent.contextMenu(screen.getByRole('gridcell', { name: '2' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '변경 내역 보기' }))
    await userEvent.click(await screen.findByRole('button', { name: '이 글로 되돌리기' }))
    expect(onSave).toHaveBeenCalledWith(r, '혼돈의 성물')
    expect(screen.queryByRole('dialog', { name: '2행 변경 내역' })).not.toBeInTheDocument()
  })

  it('원문·Fix 셀 우클릭 메뉴에도 변경 내역 보기가 있다', () => {
    const r = row(0, { fix: '성물' })
    renderGrid([r])
    fireEvent.contextMenu(screen.getByText(r.src))
    expect(screen.getByRole('menuitem', { name: '변경 내역 보기' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.contextMenu(screen.getByText('성물'))
    expect(screen.getByRole('menuitem', { name: '변경 내역 보기' })).toBeInTheDocument()
  })

  it('칸은 마우스를 누르는 순간 골라지고, 고른 칸만 글자를 고를 수 있다', () => {
    renderGrid([row(0), row(1)])
    const bar = screen.getByRole('region', { name: '선택한 칸 내용' })
    const src = screen.getAllByRole('gridcell')[2]
    fireEvent.mouseDown(src, { button: 0 })
    expect(bar).toHaveTextContent('2행 · 간체 원문')
    expect(screen.getByRole('grid').querySelector('table')!.className).toContain('select-none')
    expect(src.className).toContain('select-text')
    expect(screen.getByRole('gridcell', { name: '2행 Fix' }).className).not.toContain('select-text')
  })

  it('우클릭은 칸을 바꾸지 않는다', () => {
    renderGrid([row(0)])
    const bar = screen.getByRole('region', { name: '선택한 칸 내용' })
    fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
    fireEvent.mouseDown(screen.getAllByRole('gridcell')[2], { button: 2 })
    expect(bar).toHaveTextContent('2행 · 수정 한국어 (Fix)')
  })

  it('체크 칸을 한 번 누르면 체크가 한 번만 바뀐다', async () => {
    const { props } = renderGrid([row(0)])
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 A' }))
    expect(props.onMarks).toHaveBeenCalledTimes(1)
    expect(props.onMarks).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), { review1: true })
  })

  it('Ctrl/⌘+A는 고른 칸의 값 글자만 고른다 — 배지는 빼고, 한글 자판이어도', () => {
    renderGrid([row(0, { fix: '아군의 생명를 회복', josaHints: [{ at: 4, from: '생명를', to: '생명을', kind: 'HANGUL' }] }), row(1)])
    const grid = screen.getByRole('grid')
    fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
    fireEvent.keyDown(grid, { key: 'ㅁ', code: 'KeyA', metaKey: true })
    expect(window.getSelection()!.toString()).toBe('아군의 생명를 회복')
    fireEvent.keyDown(grid, { key: 'ArrowLeft' })   // 기존 한국어
    fireEvent.keyDown(grid, { key: 'a', code: 'KeyA', ctrlKey: true })
    expect(window.getSelection()!.toString()).toBe('혼돈의 성물')
  })

  it('체크 칸·# 칸에서 Ctrl+A는 아무것도 고르지 않고 화면 전체 선택도 막는다', () => {
    renderGrid([row(0)])
    const grid = screen.getByRole('grid')
    fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 A' }), { button: 0 })
    const ev = new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', ctrlKey: true, bubbles: true, cancelable: true })
    grid.dispatchEvent(ev)
    expect(ev.defaultPrevented).toBe(true)
    expect(window.getSelection()!.toString()).toBe('')
  })

  it('고른 칸이 화면 밖(가상 스크롤로 DOM에 없음)이어도 Ctrl+A는 오류 없이 지나간다', () => {
    const rows = Array.from({ length: 2000 }, (_, i) => row(i))
    renderGrid(rows, { restore: { cell: { row: 1999, col: 'fix' }, scrollTop: 0 } })
    const grid = screen.getByRole('grid')
    expect(() => fireEvent.keyDown(grid, { key: 'a', code: 'KeyA', ctrlKey: true })).not.toThrow()
    expect(window.getSelection()!.toString()).toBe('')
  })

  describe('Ctrl/⌘+C', () => {
    const stubClipboard = () => {
      const writeText = vi.fn(() => Promise.resolve())
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
      return writeText
    }

    it('글자를 안 골랐으면 고른 칸의 전체 값을 복사한다(저장 실패 입력이 있으면 그 값), 한글 자판이어도', () => {
      const writeText = stubClipboard()
      renderGrid([row(0)], { failedText: new Map([[100, { text: '고친 글', base: '혼돈의 성물' }]]) })
      window.getSelection()!.removeAllRanges()
      const grid = screen.getByRole('grid')
      fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
      fireEvent.keyDown(grid, { key: 'ㅊ', code: 'KeyC', metaKey: true })
      expect(writeText).toHaveBeenCalledWith('고친 글')
    })

    it('고른 칸 안에 글자를 골라 뒀으면 브라우저 기본 복사에 맡긴다', () => {
      const writeText = stubClipboard()
      renderGrid([row(0)])
      const grid = screen.getByRole('grid')
      fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
      fireEvent.keyDown(grid, { key: 'a', code: 'KeyA', ctrlKey: true })
      fireEvent.keyDown(grid, { key: 'c', code: 'KeyC', ctrlKey: true })
      expect(writeText).not.toHaveBeenCalled()
    })

    it('Ctrl+A로 고른 뒤 다른 칸으로 옮기면 지금 칸 값을 복사한다', () => {
      const writeText = stubClipboard()
      renderGrid([row(0, { src: '圣物' })])
      const grid = screen.getByRole('grid')
      fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
      fireEvent.keyDown(grid, { key: 'a', code: 'KeyA', ctrlKey: true })
      fireEvent.keyDown(grid, { key: 'ArrowLeft' })   // 기존 한국어
      fireEvent.keyDown(grid, { key: 'ArrowLeft' })   // 영어
      fireEvent.keyDown(grid, { key: 'ArrowLeft' })   // 원문
      expect(window.getSelection()!.toString()).toBe('')
      fireEvent.keyDown(grid, { key: 'c', code: 'KeyC', ctrlKey: true })
      expect(writeText).toHaveBeenCalledWith('圣物')
    })

    it('체크 칸에서는 아무것도 복사하지 않는다', () => {
      const writeText = stubClipboard()
      renderGrid([row(0)])
      window.getSelection()!.removeAllRanges()
      const grid = screen.getByRole('grid')
      fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 A' }), { button: 0 })
      fireEvent.keyDown(grid, { key: 'c', code: 'KeyC', ctrlKey: true })
      expect(writeText).not.toHaveBeenCalled()
    })

    it('고른 칸이 화면 밖이어도 데이터로 값을 복사한다', () => {
      const writeText = stubClipboard()
      const rows = Array.from({ length: 2000 }, (_, i) => row(i, { fix: `행 ${i}` }))
      renderGrid(rows, { restore: { cell: { row: 1999, col: 'fix' }, scrollTop: 0 } })
      window.getSelection()!.removeAllRanges()
      fireEvent.keyDown(screen.getByRole('grid'), { key: 'c', code: 'KeyC', ctrlKey: true })
      expect(writeText).toHaveBeenCalledWith('행 1999')
    })
  })

  it('원문·영어·기존 한국어·ID를 고르면 위 입력줄은 읽기 전용 글 상자다 — ⌘A·⌘C가 그 안에서만 되고 Esc로 표로 돌아간다', async () => {
    renderGrid([row(0)])
    const bar = screen.getByRole('region', { name: '선택한 칸 내용' })
    fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
    fireEvent.keyDown(screen.getByRole('grid'), { key: 'ArrowLeft' })   // 기존 한국어
    const view = within(bar).getByRole('textbox', { name: '선택한 칸 내용 보기' })
    expect(view).toHaveValue('혼돈의 성물')
    expect(view).toHaveAttribute('readonly')
    await userEvent.click(view)
    await userEvent.type(view, 'X')
    expect(view).toHaveValue('혼돈의 성물')
    fireEvent.keyDown(view, { key: 'Escape' })
    expect(screen.getByRole('grid')).toHaveFocus()
    // # 칸·체크 칸은 보일 글이 없어 글 상자가 없다
    fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 A' }), { button: 0 })
    expect(within(bar).queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('위 입력줄에서 고치다 글이 아닌 칸을 눌러도 입력을 저장하고 키보드가 그대로 된다', async () => {
    const { props } = renderGrid([row(0), row(1)])
    const bar = screen.getByRole('region', { name: '선택한 칸 내용' })
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 Fix' }))
    const input = within(bar).getByRole('textbox', { name: '선택한 칸 편집' })
    await userEvent.click(input)
    await userEvent.type(input, '!')
    // 실제 브라우저에서는 mousedown 처리 중 다시 그려져 입력줄이 blur 전에 사라진다 — focus 이동 없이 mousedown만 보낸다
    fireEvent.mouseDown(screen.getAllByRole('gridcell')[2], { button: 0 })
    expect(props.onSave).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), '혼돈의 성물!')
    fireEvent.keyDown(screen.getByRole('grid'), { key: 'ArrowDown' })
    expect(bar).toHaveTextContent('3행 · 간체 원문')
  })

  it('최종 리뷰 시트는 최종 수정 열을 보이고, 표시한 행만 Fix를 연두로 칠한다', async () => {
    const { props } = renderGrid([row(0, { changed: true, finalReview: true }), row(1, { changed: true, finalReview: true, finalMark: true })], { finalReview: true })
    expect(screen.getByRole('columnheader', { name: '최종 수정' })).toBeInTheDocument()
    expect(screen.getByRole('gridcell', { name: '2행 Fix' }).className).not.toMatch(/fff7d6|e6f4d7/)
    expect(screen.getByRole('gridcell', { name: '3행 Fix' }).className).toMatch(/e6f4d7/)
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 최종 수정' }))
    expect(props.onMarks).toHaveBeenCalledWith(expect.objectContaining({ id: 100 }), { finalMark: true })
  })

  it('일반 시트는 최종 수정 열이 없고 달라진 Fix는 노랑 그대로', () => {
    renderGrid([row(0, { changed: true })])
    expect(screen.queryByRole('columnheader', { name: '최종 수정' })).toBeNull()
    expect(screen.getByRole('gridcell', { name: '2행 Fix' }).className).toMatch(/fff7d6/)
  })
})
