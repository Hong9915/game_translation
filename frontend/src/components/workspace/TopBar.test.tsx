import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { TopBar } from './TopBar'

const base = {
  fileName: 'a.xlsx', gameName: '게임', gameId: 1, projectId: 2, reviewer: null, presence: [], status: 'open' as const,
  exporting: false, glossaryOpen: false, activityOpen: false, onRename: () => {}, onExport: () => {}, onToggleGlossary: () => {}, onToggleActivity: () => {},
}

describe('TopBar', () => {
  it('showsProposalChipOnlyWhenPending', async () => {
    const onOpenProposals = vi.fn()
    const { rerender } = render(<MemoryRouter><TopBar {...base} proposalCount={2} onOpenProposals={onOpenProposals} /></MemoryRouter>)
    const buttons = screen.getAllByRole('button').map((b) => b.textContent)
    expect(buttons.indexOf('Claude 제안 2')).toBe(buttons.indexOf('메모') - 1)
    await userEvent.click(screen.getByRole('button', { name: 'Claude 제안 2' }))
    expect(onOpenProposals).toHaveBeenCalledTimes(1)
    rerender(<MemoryRouter><TopBar {...base} proposalCount={0} onOpenProposals={onOpenProposals} /></MemoryRouter>)
    expect(screen.queryByRole('button', { name: /Claude 제안/ })).toBeNull()
    rerender(<MemoryRouter><TopBar {...base} /></MemoryRouter>)
    expect(screen.queryByRole('button', { name: /Claude 제안/ })).toBeNull()
  })

  it('glossaryBadgeShowsPendingCount', () => {
    const { rerender } = render(<MemoryRouter><TopBar {...base} glossaryPending={2} /></MemoryRouter>)
    expect(screen.getByLabelText('승인 대기 2')).toBeInTheDocument()
    rerender(<MemoryRouter><TopBar {...base} glossaryPending={0} /></MemoryRouter>)
    expect(screen.queryByLabelText(/승인 대기/)).toBeNull()
  })

  it('메모 버튼은 게임의 메모를 새 창으로 열고, 그 오른쪽에 교정 규칙이 있다', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    render(<MemoryRouter><TopBar {...base} /></MemoryRouter>)
    const buttons = screen.getAllByRole('button').map((b) => b.textContent)
    expect(buttons.indexOf('교정 규칙')).toBe(buttons.indexOf('메모') + 1)
    expect(buttons.indexOf('단어장')).toBe(buttons.indexOf('교정 규칙') + 1)
    await userEvent.click(screen.getByRole('button', { name: '메모' }))
    expect(open).toHaveBeenCalledWith('/games/1/memo', 'memo-1', expect.stringContaining('popup'))
    open.mockRestore()
  })

  it('교정 규칙 버튼은 게임의 교정 규칙 창을 새 탭으로 연다', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    render(<MemoryRouter><TopBar {...base} /></MemoryRouter>)
    await userEvent.click(screen.getByRole('button', { name: '교정 규칙' }))
    expect(open).toHaveBeenCalledWith('/games/1/rules', 'rules-1')
    open.mockRestore()
  })

  it('게임을 아직 모르면 메모를 열 수 없다', () => {
    render(<MemoryRouter><TopBar {...base} gameId={0} /></MemoryRouter>)
    expect(screen.getByRole('button', { name: '메모' })).toBeDisabled()
  })

  it('단어장 옆에 변경 내역 버튼이 있고 누르면 토글을 부른다', async () => {
    const onToggleActivity = vi.fn()
    render(<MemoryRouter><TopBar {...base} onToggleActivity={onToggleActivity} /></MemoryRouter>)
    const buttons = screen.getAllByRole('button').map((b) => b.textContent)
    expect(buttons.indexOf('변경 내역')).toBe(buttons.indexOf('단어장') + 1)
    await userEvent.click(screen.getByRole('button', { name: '변경 내역' }))
    expect(onToggleActivity).toHaveBeenCalledTimes(1)
  })

  it('열려 있으면 단어장과 같은 강조색', () => {
    render(<MemoryRouter><TopBar {...base} activityOpen /></MemoryRouter>)
    expect(screen.getByRole('button', { name: '변경 내역' }).className).toContain('bg-purple')
    expect(screen.getByRole('button', { name: '단어장' }).className).not.toContain('bg-purple')
  })
})
