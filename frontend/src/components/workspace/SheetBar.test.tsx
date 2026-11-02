import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import { SheetBar } from './SheetBar'

const sheets = [{ sheet: 'UI', total: 1204, changed: 38, review1: 0, review2: 412, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }, { sheet: 'Skill', total: 863, changed: 0, review1: 0, review2: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }]

describe('SheetBar', () => {
  it('shows how many rows are still waiting for the josa check', () => {
    const { rerender } = render(<SheetBar sheets={[]} active={null} filter="ALL" q="" hasReviewer={false} onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} josaPending={49565} />)
    expect(screen.getByText('조사 검사 중 · 49,565행 남음')).toBeInTheDocument()
    rerender(<SheetBar sheets={[]} active={null} filter="ALL" q="" hasReviewer={false} onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} josaPending={0} />)
    expect(screen.queryByText(/조사 검사 중/)).toBeNull()
  })

  it('shows the josa count in the filter option', () => {
    render(<SheetBar sheets={[]} active={null} filter="ALL" q="" hasReviewer={false} onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} josaCount={136} />)
    expect(screen.getByRole('option', { name: '조사 확인 (136)' })).toBeInTheDocument()
  })

  it('renders a tab per sheet with its row count and marks the active one', () => {
    render(<SheetBar sheets={sheets} active="Skill" filter="ALL" q="" hasReviewer onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(2)
    expect(tabs[0]).toHaveTextContent('UI')
    expect(tabs[0]).toHaveTextContent('1,204')
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true')
  })

  it('switches sheet and filter, and hides 내 담당 without a reviewer', async () => {
    const onSheet = vi.fn(), onFilter = vi.fn()
    render(<SheetBar sheets={sheets} active="UI" filter="ALL" q="" hasReviewer={false} onSheet={onSheet} onFilter={onFilter} onSearch={vi.fn()} />)
    await userEvent.click(screen.getByRole('tab', { name: /Skill/ }))
    expect(onSheet).toHaveBeenCalledWith('Skill')
    const filter = screen.getByRole('combobox', { name: '필터' })
    expect(screen.queryByRole('option', { name: '내 담당' })).not.toBeInTheDocument()
    await userEvent.selectOptions(filter, 'CHANGED')
    expect(onFilter).toHaveBeenCalledWith('CHANGED')
  })

  it('offers the duplicate-conflict and review-mark filters, and no 미완료', () => {
    render(<SheetBar sheets={sheets} active="UI" filter="ALL" q="" hasReviewer onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} />)
    expect(screen.getByRole('option', { name: '동일 원문 불일치' })).toBeInTheDocument()
    for (const name of ['A 안 함', 'B 안 함', '확정 안 함', '확인 필요']) expect(screen.getByRole('option', { name })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '미완료' })).not.toBeInTheDocument()
  })

  it('debounces search input', () => {
    vi.useFakeTimers()
    try {
      const onSearch = vi.fn()
      render(<SheetBar sheets={sheets} active="UI" filter="ALL" q="" hasReviewer onSheet={vi.fn()} onFilter={vi.fn()} onSearch={onSearch} />)
      const input = screen.getByRole('searchbox', { name: '검색' })
      fireEvent.change(input, { target: { value: '성' } })
      act(() => { vi.advanceTimersByTime(200) })
      fireEvent.change(input, { target: { value: '성물' } })
      act(() => { vi.advanceTimersByTime(299) })
      expect(onSearch).not.toHaveBeenCalled()
      act(() => { vi.advanceTimersByTime(1) })
      expect(onSearch).toHaveBeenCalledTimes(1)
      expect(onSearch).toHaveBeenCalledWith('성물')
    } finally {
      vi.useRealTimers()
    }
  })

  describe('불일치 다시 계산', () => {
    afterEach(() => { vi.restoreAllMocks() })

    // 버튼이 쿼리 클라이언트로 목록·요약 캐시를 무효화하므로 Provider로 감싼다
    const renderWithClient = () => {
      const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      render(
        <QueryClientProvider client={client}>
          <SheetBar sheets={sheets} active="Skill" filter="ALL" q="" hasReviewer onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} projectId={7} recomputeEnabled />
        </QueryClientProvider>,
      )
      return invalidate
    }

    it('recomputes the active sheet, disables itself meanwhile, then shows the count and refreshes rows and summary', async () => {
      let resolve!: (v: { count: number }) => void
      const spy = vi.spyOn(api.workspace, 'recomputeTermMiss').mockReturnValue(new Promise((r) => { resolve = r }))
      const invalidate = renderWithClient()
      const button = screen.getByRole('button', { name: '불일치 다시 계산' })

      await userEvent.click(button)
      expect(spy).toHaveBeenCalledWith(7, 'Skill')
      await waitFor(() => expect(button).toBeDisabled())

      await act(async () => { resolve({ count: 3 }) })
      expect(await screen.findByRole('status')).toHaveTextContent('불일치 3행 갱신')
      expect(button).toBeEnabled()
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workspace-rows', 7] })
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workspace-summary', 7] })
    })

    it('shows the server message when recompute fails', async () => {
      vi.spyOn(api.workspace, 'recomputeTermMiss').mockRejectedValue(new ApiError(400, '다시 계산할 시트를 고르세요.'))
      const invalidate = renderWithClient()
      await userEvent.click(screen.getByRole('button', { name: '불일치 다시 계산' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('다시 계산할 시트를 고르세요.')
      expect(invalidate).not.toHaveBeenCalled()
    })

    it('recomputeEnabled 없이는 버튼을 아예 그리지 않는다 — 늘 꺼져 있는 버튼은 자리만 차지한다', () => {
      render(
        <QueryClientProvider client={new QueryClient()}>
          <SheetBar sheets={sheets} active="Skill" filter="ALL" q="" hasReviewer onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} projectId={7} />
        </QueryClientProvider>,
      )
      expect(screen.queryByRole('button', { name: '불일치 다시 계산' })).not.toBeInTheDocument()
    })

    it('hides the button without a project', () => {
      render(<SheetBar sheets={sheets} active="Skill" filter="ALL" q="" hasReviewer onSheet={vi.fn()} onFilter={vi.fn()} onSearch={vi.fn()} />)
      expect(screen.queryByRole('button', { name: '불일치 다시 계산' })).not.toBeInTheDocument()
    })
  })

  it('최종 수정 필터는 최종 리뷰 시트에서만', () => {
    const props = { sheets, active: 'UI', filter: 'ALL' as const, q: '', hasReviewer: false, onSheet: vi.fn(), onFilter: vi.fn(), onSearch: vi.fn() }
    const { rerender } = render(<SheetBar {...props} />)
    expect(screen.queryByRole('option', { name: '최종 수정 표시' })).toBeNull()
    rerender(<SheetBar {...props} finalReview />)
    expect(screen.getByRole('option', { name: '최종 수정 표시' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '최종 수정 안 함' })).toBeInTheDocument()
  })
})
