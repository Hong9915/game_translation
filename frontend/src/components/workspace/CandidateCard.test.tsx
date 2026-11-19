import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { GlossaryCandidate } from '../../api/types'
import CandidateCard from './CandidateCard'

const c: GlossaryCandidate = { id: 1, gameId: 3, src: '关卡', ko: '스테이지', oldKo: '관문', rows: 12, reason: null, source: 'CONNECTOR', status: 'PENDING',
  createdBy: 1, createdByName: '형준', createdAt: '2026-11-18T05:07:00', conflictKo: '관문' }

describe('CandidateCard', () => {
  it('showsSourceConflictAndOldKo', async () => {
    const onApprove = vi.fn(), onDismiss = vi.fn()
    render(<CandidateCard c={c} onApprove={onApprove} onDismiss={onDismiss} busy={false} />)
    expect(screen.getByText('关卡 → 스테이지')).toBeInTheDocument()
    expect(screen.getByText('전: 관문')).toBeInTheDocument()
    expect(screen.getByText(/12행 · 형준 · Claude · 11\/18 05:07/)).toBeInTheDocument()
    expect(screen.getByText("⚠ 단어장엔 '관문'으로 있음")).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '승인' }))
    await userEvent.click(screen.getByRole('button', { name: '버림' }))
    expect(onApprove).toHaveBeenCalledTimes(1)
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('conflictParticleFollowsFinalConsonant', () => {
    const { rerender } = render(<CandidateCard c={{ ...c, conflictKo: '스테이지' }} onApprove={() => {}} onDismiss={() => {}} busy={false} />)
    expect(screen.getByText("⚠ 단어장엔 '스테이지'로 있음")).toBeInTheDocument()
    rerender(<CandidateCard c={{ ...c, conflictKo: '관문' }} onApprove={() => {}} onDismiss={() => {}} busy={false} />)
    expect(screen.getByText("⚠ 단어장엔 '관문'으로 있음")).toBeInTheDocument()
    rerender(<CandidateCard c={{ ...c, conflictKo: '길' }} onApprove={() => {}} onDismiss={() => {}} busy={false} />)
    expect(screen.getByText("⚠ 단어장엔 '길'로 있음")).toBeInTheDocument()
    rerender(<CandidateCard c={{ ...c, conflictKo: 'HP' }} onApprove={() => {}} onDismiss={() => {}} busy={false} />)
    expect(screen.getByText("⚠ 단어장엔 'HP'(으)로 있음")).toBeInTheDocument()
  })

  it('commandSourceLabel', () => {
    render(<CandidateCard c={{ ...c, source: 'COMMAND', createdByName: null, oldKo: null, conflictKo: null }} onApprove={() => {}} onDismiss={() => {}} busy={false} />)
    expect(screen.getByText(/알 수 없음 · 명령으로 고치기/)).toBeInTheDocument()
    expect(screen.queryByText(/^전:/)).toBeNull()
    expect(screen.queryByText(/⚠/)).toBeNull()
  })
})
