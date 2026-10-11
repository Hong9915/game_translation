import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PageBar, ViewSizePicker } from './PageBar'

describe('PageBar', () => {
  it('지금 쪽 번호와 보이는 행 구간을 보여 준다', () => {
    render(<PageBar page={2} size={100} total={49566} onPage={() => {}} />)
    expect(screen.getByRole('textbox', { name: '쪽 번호' })).toHaveValue('3')
    expect(screen.getByText('/ 496')).toBeInTheDocument()
    expect(screen.getByText('201–300 / 49,566행')).toBeInTheDocument()
  })

  it('처음·이전·다음·마지막 버튼으로 쪽을 옮긴다', async () => {
    const onPage = vi.fn()
    render(<PageBar page={2} size={100} total={49566} onPage={onPage} />)
    await userEvent.click(screen.getByRole('button', { name: '처음 쪽' }))
    await userEvent.click(screen.getByRole('button', { name: '이전 쪽' }))
    await userEvent.click(screen.getByRole('button', { name: '다음 쪽' }))
    await userEvent.click(screen.getByRole('button', { name: '마지막 쪽' }))
    expect(onPage.mock.calls.map((c) => c[0])).toEqual([0, 1, 3, 495])
  })

  it('첫 쪽에서는 처음·이전을, 마지막 쪽에서는 다음·마지막을 막는다', () => {
    const { rerender } = render(<PageBar page={0} size={50} total={120} onPage={() => {}} />)
    expect(screen.getByRole('button', { name: '처음 쪽' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '이전 쪽' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다음 쪽' })).toBeEnabled()
    rerender(<PageBar page={2} size={50} total={120} onPage={() => {}} />)
    expect(screen.getByRole('button', { name: '다음 쪽' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '마지막 쪽' })).toBeDisabled()
    expect(screen.getByText('101–120 / 120행')).toBeInTheDocument()
  })

  it('쪽 번호를 입력하고 Enter를 누르면 그 쪽으로 가고, 범위를 넘으면 끝 쪽으로 맞춘다', async () => {
    const onPage = vi.fn()
    render(<PageBar page={0} size={100} total={1000} onPage={onPage} />)
    const box = screen.getByRole('textbox', { name: '쪽 번호' })
    await userEvent.clear(box)
    await userEvent.type(box, '7{Enter}')
    expect(onPage).toHaveBeenLastCalledWith(6)
    await userEvent.clear(box)
    await userEvent.type(box, '999{Enter}')
    expect(onPage).toHaveBeenLastCalledWith(9)
  })

  it('숫자가 아닌 입력은 지금 쪽 번호로 되돌린다', async () => {
    const onPage = vi.fn()
    render(<PageBar page={4} size={100} total={1000} onPage={onPage} />)
    const box = screen.getByRole('textbox', { name: '쪽 번호' })
    await userEvent.clear(box)
    await userEvent.type(box, 'abc{Enter}')
    expect(onPage).not.toHaveBeenCalled()
    expect(box).toHaveValue('5')
  })

  it('행이 없으면 1 / 1쪽, 0행으로 보인다', () => {
    render(<PageBar page={0} size={100} total={0} onPage={() => {}} />)
    expect(screen.getByText('/ 1')).toBeInTheDocument()
    expect(screen.getByText('0 / 0행')).toBeInTheDocument()
  })
})

describe('ViewSizePicker', () => {
  it('고른 보기를 눌린 상태로 보이고, 다른 보기를 누르면 알린다', async () => {
    const onChange = vi.fn()
    render(<ViewSizePicker value={100} onChange={onChange} />)
    expect(screen.getByRole('button', { name: '100행' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '전체' })).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(screen.getByRole('button', { name: '50행' }))
    await userEvent.click(screen.getByRole('button', { name: '전체' }))
    expect(onChange.mock.calls.map((c) => c[0])).toEqual([50, 'all'])
  })
})
