import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ListNav, PageSize, Pager } from './Pager'

describe('Pager', () => {
  it('shows the range and moves pages', async () => {
    const onPage = vi.fn()
    render(<Pager page={1} size={50} total={3881} onPage={onPage} />)
    expect(screen.getByText('51–100 / 3,881')).toBeInTheDocument()
    expect(screen.getByText('2 / 78')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onPage).toHaveBeenLastCalledWith(2)
  })

  it('page size select resets to the first page when the size changes', async () => {
    const onPage = vi.fn(); const onSize = vi.fn()
    render(<PageSize size={50} onSize={onSize} onPage={onPage} />)
    await userEvent.selectOptions(screen.getByLabelText('페이지 크기'), '200')
    expect(onSize).toHaveBeenCalledWith(200)
    expect(onPage).toHaveBeenLastCalledWith(0)
  })

  it('disables prev on the first page and next on the last', () => {
    render(<Pager page={0} size={50} total={40} onPage={() => {}} />)
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    expect(screen.getByText('1–40 / 40')).toBeInTheDocument()
  })
})

describe('ListNav', () => {
  it('sticks to the top and combines page size with prev/next', async () => {
    const onPage = vi.fn(); const onSize = vi.fn()
    const { container } = render(<ListNav page={1} size={50} total={3881} onPage={onPage} onSize={onSize} />)
    expect(container.firstElementChild?.className).toContain('sticky')
    expect(screen.getByText('51–100 / 3,881')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(onPage).toHaveBeenLastCalledWith(2)
    await userEvent.selectOptions(screen.getByLabelText('페이지 크기'), '200')
    expect(onSize).toHaveBeenCalledWith(200)
    expect(onPage).toHaveBeenLastCalledWith(0)
  })

  it('shows only the page size until the total is known', () => {
    render(<ListNav page={0} size={50} onPage={() => {}} onSize={() => {}} />)
    expect(screen.getByLabelText('페이지 크기')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument()
  })
})
