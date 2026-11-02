import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { JosaApplyDialog } from './JosaApplyDialog'

function wrap(ui: ReactNode) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>)
}

describe('JosaApplyDialog', () => {
  it('defaults hangul and number on, tag off, and sends the chosen kinds', async () => {
    const josaPreview = vi.fn().mockResolvedValue({
      counts: { HANGUL: 77, NUMBER: 45, TAG: 14 },
      examples: { HANGUL: ['생명를 → 생명을'], NUMBER: ['3로 → 3으로'], TAG: [']을 → ]를'] },
    })
    const apply = vi.fn().mockResolvedValue({ changed: 120, skipped: 2 })
    const review = vi.spyOn(api, 'review').mockReturnValue({ josaPreview, josaApply: apply } as unknown as ReturnType<typeof api.review>)
    const onDone = vi.fn()
    wrap(<JosaApplyDialog x={0} y={0} target={{ kind: 'project', projectId: 7, sheet: 'Skill' }} onDone={onDone} onClose={vi.fn()} />)
    expect(await screen.findByText('생명를 → 생명을')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /한글 뒤/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /숫자 뒤/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /태그 뒤/ })).not.toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: '122곳 고치기' }))
    expect(review).toHaveBeenCalledWith({ kind: 'project', projectId: 7, sheet: 'Skill' })
    expect(apply).toHaveBeenCalledWith({ kinds: ['HANGUL', 'NUMBER'] })
    expect(onDone).toHaveBeenCalledWith({ changed: 120, skipped: 2 })
  })
})
