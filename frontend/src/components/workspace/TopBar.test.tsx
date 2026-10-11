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
