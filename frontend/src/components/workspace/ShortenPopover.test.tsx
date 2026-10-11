import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { RowShortenResult } from '../../api/types'
import { ShortenPopover } from './ShortenPopover'

const result = (over: Partial<RowShortenResult> = {}): RowShortenResult => ({
  ko: '적에게 큰 피해를 입힙니다', len: 11, target: 8,
  candidates: [{ ko: '적에게 큰 피해를 입힘', len: 10 }, { ko: '적에게 큰 피해', len: 6 }], ...over,
})

describe('ShortenPopover', () => {
  it('asks with the default target, shows candidates with counts and marks the ones over the target', async () => {
    const onShorten = vi.fn().mockResolvedValue(result())
    render(<ShortenPopover x={0} y={0} onShorten={onShorten} onPick={vi.fn()} onClose={vi.fn()} />)
    expect(onShorten).toHaveBeenCalledWith(undefined)
    expect(screen.getByText(/GPT가 줄이는 중/)).toBeInTheDocument()

    const over = await screen.findByRole('button', { name: /적에게 큰 피해를 입힘/ })
    expect(over).toHaveTextContent('10자')
    expect(over.querySelector('[data-over]')).not.toBeNull()
    const fits = screen.getByRole('button', { name: /^적에게 큰 피해\s*6자$/ })
    expect(fits.querySelector('[data-over]')).toBeNull()
    expect(screen.getByText(/현재 11자/)).toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: '목표 글자 수' })).toHaveValue(8)
  })

  it('picking a candidate hands it over', async () => {
    const onPick = vi.fn()
    render(<ShortenPopover x={0} y={0} onShorten={vi.fn().mockResolvedValue(result())} onPick={onPick} onClose={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: /^적에게 큰 피해\s*6자$/ }))
    expect(onPick).toHaveBeenCalledWith('적에게 큰 피해')
  })

  it('asks again with the typed target', async () => {
    const onShorten = vi.fn().mockResolvedValueOnce(result()).mockResolvedValueOnce(result({ target: 5, candidates: [{ ko: '큰 피해', len: 3 }] }))
    render(<ShortenPopover x={0} y={0} onShorten={onShorten} onPick={vi.fn()} onClose={vi.fn()} />)
    const box = await screen.findByRole('spinbutton', { name: '목표 글자 수' })
    await userEvent.clear(box)
    await userEvent.type(box, '5')
    await userEvent.click(screen.getByRole('button', { name: '다시 줄이기' }))
    expect(onShorten).toHaveBeenLastCalledWith(5)
    expect(await screen.findByRole('button', { name: /큰 피해\s*3자/ })).toBeInTheDocument()
  })

  it('shows the server message on failure and closes on Escape', async () => {
    const onClose = vi.fn()
    render(<ShortenPopover x={0} y={0} onShorten={vi.fn().mockRejectedValue(new Error('줄인 후보를 만들지 못했습니다.'))} onPick={vi.fn()} onClose={onClose} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('줄인 후보를 만들지 못했습니다.')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('closes on a click outside, but not on a click inside', async () => {
    const onClose = vi.fn()
    render(<div><button type="button">바깥</button><ShortenPopover x={0} y={0} onShorten={vi.fn().mockResolvedValue(result())} onPick={vi.fn()} onClose={onClose} /></div>)
    await screen.findByRole('button', { name: /^적에게 큰 피해\s*6자$/ })
    fireEvent.mouseDown(screen.getByRole('spinbutton', { name: '목표 글자 수' }))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.mouseDown(screen.getByRole('button', { name: '바깥' }))
    expect(onClose).toHaveBeenCalled()
  })

  it('closes on Escape even when focus is outside the popover', async () => {
    const onClose = vi.fn()
    render(<ShortenPopover x={0} y={0} onShorten={vi.fn().mockResolvedValue(result())} onPick={vi.fn()} onClose={onClose} />)
    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})
