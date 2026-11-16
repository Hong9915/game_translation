import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { StatusBar } from './StatusBar'

const summary = {
  sheets: [{ sheet: 'UI', total: 1204, changed: 38, review1: 400, review2: 412, review3: 0, needsCheck: 9, confirmed: 0, josa: 0, josaPending: 0 }], total: 1204, changed: 38, review1: 400, review2: 412, review3: 0, needsCheck: 9, confirmed: 0, josa: 0, josaPending: 0, unassigned: 4,
  reviewers: [{ reviewer: { id: 7, name: '지민', color: '#0c8599' }, assigned: 400, done: 248 }],
}

describe('StatusBar', () => {
  it('shows totals, per-reviewer progress, selection and save state', () => {
    render(<StatusBar summary={summary} sheet="UI" selection="4행 Fix" saveState="saved" />)
    expect(screen.getByText('1,204행 · 수정됨 38 · A 400 · B 412 · C 0 · 확인 필요 9 · 확정 0')).toBeInTheDocument()
    expect(screen.getByText(/⌘Enter A/)).toBeInTheDocument()
    expect(screen.getByText('지민')).toBeInTheDocument()
    expect(screen.getByText('248/400')).toBeInTheDocument()
    expect(screen.getByText('미배정 4')).toBeInTheDocument()
    expect(screen.getByText('4행 Fix')).toBeInTheDocument()
    expect(screen.getByText('저장됨')).toBeInTheDocument()
  })

  it('# 칸 색이 누구인지 이 화면에서 고친 사람을 늘어놓는다', () => {
    render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" editors={[{ id: 7, name: '지민', color: '#0c8599' }, { id: 8, name: '하준', color: '#e8590c' }]} />)
    expect(screen.getByRole('group', { name: '# 칸 색: 마지막으로 고친 사람' })).toHaveTextContent('고친 사람지민하준')
  })

  it('고친 사람이 없으면 범례를 숨긴다', () => {
    render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" editors={[]} />)
    expect(screen.queryByRole('group', { name: '# 칸 색: 마지막으로 고친 사람' })).not.toBeInTheDocument()
  })

  it('shows an error with a dismiss button', () => {
    render(<StatusBar summary={summary} sheet="UI" selection="" saveState="error" error="모르는 검토자입니다" onDismissError={() => {}} />)
    expect(screen.getByRole('alert')).toHaveTextContent('모르는 검토자입니다')
    expect(screen.getByRole('button', { name: '닫기' })).toBeInTheDocument()
  })

  it('shows a propagation notice for 4 seconds when rows changed elsewhere', () => {
    vi.useFakeTimers()
    try {
      render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={2} />)
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(3999) })
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(1) })
      expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows no propagation notice when propagated is 0 or absent', () => {
    render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={0} />)
    expect(screen.queryByText(/함께 수정됨/)).not.toBeInTheDocument()
  })

  it('shows the notice again for a repeated identical count once the previous notice has expired (버그: Object.is로 인한 no-op)', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={2} propagatedSeq={1} />)
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(4000) })
      expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument()

      // 두 번째 저장도 건수가 똑같이 2행이다 — propagated 값만 보면 지난번과 같아 setState가 no-op일 수 있다.
      // propagatedSeq가 바뀌었으니 그래도 다시 보여야 한다.
      rerender(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={2} propagatedSeq={2} />)
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(3999) })
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(1) })
      expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('resets to a fresh 4-second window when a new propagation of the same count arrives while the notice is still visible', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={2} propagatedSeq={1} />)
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(3000) })   // 첫 알림이 아직 안 사라진 시점

      rerender(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={2} propagatedSeq={2} />)
      act(() => { vi.advanceTimersByTime(3000) })   // 첫 트리거 기준으론 6000ms(진작 사라졌어야) 지만, 두 번째 트리거 기준으론 3000ms
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(999) })   // 두 번째 트리거 기준 3999ms
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(1) })   // 두 번째 트리거 기준 4000ms
      expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('전파 없는(0) 저장이 뒤따르면 떠 있던 알림을 바로 내리고, 그 뒤로도 계속 사라진 채 있는다 (버그: cleanup만 하고 안 숨김)', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={2} propagatedSeq={1} />)
      expect(screen.getByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
      act(() => { vi.advanceTimersByTime(1000) })   // 아직 4초 창 안 — 이전 알림이 살아 있는 상태에서 다음 저장이 온다

      // 다음 저장은 이 행만 바뀌어 propagated가 0이다 — 앞선 저장 몫으로 떠 있던 알림은 더 이상 최신이 아니므로 바로 내려야 한다.
      rerender(<StatusBar summary={summary} sheet="UI" selection="" saveState="saved" propagated={0} propagatedSeq={2} />)
      expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument()

      // 타이머가 더 지나도(옛 4초 타이머가 남아 있었다면 다시 나타났을 시점 포함) 계속 사라진 채여야 한다.
      act(() => { vi.advanceTimersByTime(10000) })
      expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('최종 리뷰 시트면 최종 수정 수를 보인다', () => {
    const { rerender } = render(<StatusBar summary={{ ...summary, finalMark: 12 }} sheet="UI" selection="" saveState="saved" />)
    expect(screen.queryByText(/최종 수정 12/)).toBeNull()
    rerender(<StatusBar summary={{ ...summary, finalMark: 12 }} sheet="UI" selection="" saveState="saved" finalReview />)
    expect(screen.getByText(/최종 수정 12/)).toBeInTheDocument()
  })
})
