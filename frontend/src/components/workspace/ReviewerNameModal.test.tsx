import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ReviewerNameModal } from './ReviewerNameModal'

describe('ReviewerNameModal', () => {
  it('submits the trimmed name and ignores blank input', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<ReviewerNameModal open onSubmit={onSubmit} />)
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(onSubmit).not.toHaveBeenCalled()
    await userEvent.type(screen.getByLabelText('이름'), '  지민 ')
    await userEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(onSubmit).toHaveBeenCalledWith('지민')
  })

  it('renders nothing when closed', () => {
    render(<ReviewerNameModal open={false} onSubmit={vi.fn()} />)
    expect(screen.queryByLabelText('이름')).toBeNull()
  })

  it('forgets text typed and abandoned last time it was open', async () => {
    const { rerender } = render(<ReviewerNameModal open initialName="형준" onSubmit={vi.fn()} onClose={vi.fn()} />)
    await userEvent.clear(screen.getByLabelText('이름'))
    await userEvent.type(screen.getByLabelText('이름'), '엉뚱한 이름')
    rerender(<ReviewerNameModal open={false} initialName="형준" onSubmit={vi.fn()} onClose={vi.fn()} />)
    rerender(<ReviewerNameModal open initialName="형준" onSubmit={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByLabelText('이름')).toHaveValue('형준')
  })

  it('closes on ESC when it may be closed at all', async () => {
    const onClose = vi.fn()
    const { rerender } = render(<ReviewerNameModal open initialName="형준" onSubmit={vi.fn()} onClose={onClose} />)
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()

    // 이름이 아직 없어 닫을 수 없는 첫 모달은 ESC로도 닫히지 않는다
    rerender(<ReviewerNameModal open onSubmit={vi.fn()} />)
    await userEvent.keyboard('{Escape}')
    expect(screen.getByLabelText('이름')).toBeInTheDocument()
  })
})
