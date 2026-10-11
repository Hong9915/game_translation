import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { BulkMarkPopover } from './BulkMarkPopover'

describe('BulkMarkPopover', () => {
  it('says how many rows it will touch and applies check or clear', async () => {
    const onApply = vi.fn()
    render(<BulkMarkPopover x={0} y={0} label="A" count={1234} onApply={onApply} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'A 일괄 체크' })).toHaveTextContent('이 보기의 1,234행')
    await userEvent.click(screen.getByRole('button', { name: '모두 체크' }))
    expect(onApply).toHaveBeenLastCalledWith(true)
    await userEvent.click(screen.getByRole('button', { name: '모두 해제' }))
    expect(onApply).toHaveBeenLastCalledWith(false)
  })

  it('closes on 취소, Escape and a click outside', async () => {
    const onClose = vi.fn()
    render(<div><button type="button">바깥</button><BulkMarkPopover x={0} y={0} label="2차" count={3} onApply={vi.fn()} onClose={onClose} /></div>)
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    fireEvent.keyDown(document.body, { key: 'Escape' })
    fireEvent.mouseDown(screen.getByRole('button', { name: '바깥' }))
    expect(onClose).toHaveBeenCalledTimes(3)
  })
})
