import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { JosaPopover } from './JosaPopover'

const hints = [
  { at: 4, from: '생명를', to: '생명을', kind: 'HANGUL' as const },
  { at: 11, from: '면역가', to: '면역이', kind: 'HANGUL' as const },
]

describe('JosaPopover', () => {
  it('lists every hint and applies or ignores the row', async () => {
    const onApply = vi.fn(); const onIgnore = vi.fn(); const onClose = vi.fn()
    render(<JosaPopover x={0} y={0} fix="아군의 생명를 주고 면역가 오름" hints={hints} onApply={onApply} onIgnore={onIgnore} onClose={onClose} />)
    expect(screen.getByText('생명를 → 생명을')).toBeInTheDocument()
    expect(screen.getByText('면역가 → 면역이')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onApply).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '무시' }))
    expect(onIgnore).toHaveBeenCalled()
  })

})
