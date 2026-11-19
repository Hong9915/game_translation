import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReviewerRef } from '../api/types'
import { mockFetch } from '../test/mockFetch'
import { resetDeferredForTests, useConnectorProposals } from './useConnectorProposals'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners: Record<string, ((e: MessageEvent) => void)[]> = {}
  closed = false
  url: string
  readyState = 0
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { (this.listeners[name] ??= []).push(fn) }
  removeEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners[name] = (this.listeners[name] ?? []).filter((f) => f !== fn) }
  close() { this.closed = true }
  emit(name: string, data: unknown = {}) { for (const fn of this.listeners[name] ?? []) fn({ data: JSON.stringify(data) } as MessageEvent) }
}

const me1: ReviewerRef = { id: 1, name: '형준', color: '#1f4b99' }
const me2: ReviewerRef = { id: 2, name: '수진', color: '#990000' }
const sum = (id: number) => ({ id, title: null, rowCount: 2, createdAt: '2026-11-17T00:00:00Z' })
const ev = (o: object) => ({ type: 'connector-proposal', proposalId: 9, reviewerId: 1, gameId: 3, status: 'PENDING', ...o })
const listCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.includes('/connector/proposals'))

describe('useConnectorProposals', () => {
  afterEach(() => { FakeEventSource.instances = []; vi.unstubAllGlobals(); resetDeferredForTests() })

  it('loadsMyPendingAndShowsNewest', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([{ path: '/connector/proposals', body: [sum(9), sum(8)] }])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(result.current.current).toBe(9))
    expect(result.current.position).toEqual([1, 2])
    expect(calls[0].url).toContain('reviewerId=1')
    expect(calls[0].url).toContain('gameId=3')
  })

  it('noRequestWithoutGameOrReviewer', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { fn } = mockFetch([{ path: '/connector/proposals', body: [sum(9)] }])
    const a = renderHook(() => useConnectorProposals(5, null, me1))
    const b = renderHook(() => useConnectorProposals(-3, 3, null))
    await act(async () => { await Promise.resolve() })
    expect(fn).not.toHaveBeenCalled()
    expect(a.result.current.current).toBeNull()
    expect(b.result.current.current).toBeNull()
  })

  it('listErrorIsSilent', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { fn } = mockFetch([])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(fn).toHaveBeenCalled())
    await act(async () => { await Promise.resolve() })
    expect(result.current.pending).toEqual([])
    expect(result.current.current).toBeNull()
  })

  it('refetchesOnMyProposalEventOnly', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    let body = [sum(8)]
    const { calls } = mockFetch([{ path: '/connector/proposals', get body() { return body } }])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(result.current.current).toBe(8))
    const es = FakeEventSource.instances[0]
    body = [sum(9), sum(8)]
    act(() => es.emit('proposal', ev({ reviewerId: 2 })))
    act(() => es.emit('proposal', ev({ gameId: 4 })))
    await act(async () => { await Promise.resolve() })
    expect(listCalls(calls)).toHaveLength(1)
    act(() => es.emit('proposal', ev({})))
    await waitFor(() => expect(result.current.pending.map((p) => p.id)).toEqual([9, 8]))
    expect(listCalls(calls)).toHaveLength(2)
  })

  it('newProposalWhileOpenDoesNotReplaceCurrent', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    let body = [sum(8)]
    mockFetch([{ path: '/connector/proposals', get body() { return body } }])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(result.current.current).toBe(8))
    body = [sum(9), sum(8)]
    act(() => FakeEventSource.instances[0].emit('proposal', ev({})))
    await waitFor(() => expect(result.current.pending).toHaveLength(2))
    expect(result.current.current).toBe(8)
    expect(result.current.position).toEqual([2, 2])
  })

  it('laterSkipsInThisTabWithoutServer', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([{ path: '/connector/proposals', body: [sum(9), sum(8)] }])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(result.current.current).toBe(9))
    act(() => result.current.later())
    expect(result.current.current).toBe(8)
    act(() => result.current.later())
    expect(result.current.current).toBeNull()
    expect(result.current.pending).toHaveLength(2)
    expect(calls).toHaveLength(1)
    act(() => result.current.openAll())
    expect(result.current.current).toBe(9)
    expect(calls).toHaveLength(1)
  })

  it('decidedEventRemovesAndMovesOn', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    mockFetch([{ path: '/connector/proposals', body: [sum(9), sum(8)] }])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(result.current.current).toBe(9))
    act(() => FakeEventSource.instances[0].emit('proposal', { type: 'connector-proposal-decided', proposalId: 9, reviewerId: 1, gameId: 3, status: 'APPLIED' }))
    expect(result.current.pending.map((p) => p.id)).toEqual([8])
    expect(result.current.current).toBe(8)
    act(() => result.current.decided(8))
    expect(result.current.current).toBeNull()
  })

  it('switchingReviewerRefetchesAndDropsOld', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([
      { path: 'reviewerId=1', body: [sum(9)] },
      { path: 'reviewerId=2', body: [] },
    ])
    const { result, rerender } = renderHook(({ me }) => useConnectorProposals(-3, 3, me), { initialProps: { me: me1 } })
    await waitFor(() => expect(result.current.current).toBe(9))
    rerender({ me: me2 })
    expect(result.current.pending).toEqual([])
    expect(result.current.current).toBeNull()
    await waitFor(() => expect(calls.some((c) => c.url.includes('reviewerId=2'))).toBe(true))
    const n = calls.length
    act(() => FakeEventSource.instances[0].emit('proposal', ev({ reviewerId: 1 })))
    await act(async () => { await Promise.resolve() })
    expect(calls).toHaveLength(n)
  })

  it('neverReturnsOldReviewersProposalForNewReviewer', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    mockFetch([{ path: 'reviewerId=1', body: [sum(9)] }, { path: 'reviewerId=2', body: [] }])
    const seen: { me: number; current: number | null; n: number }[] = []
    const { result, rerender } = renderHook(({ me }) => {
      const r = useConnectorProposals(-3, 3, me)
      seen.push({ me: me.id, current: r.current, n: r.pending.length })
      return r
    }, { initialProps: { me: me1 } })
    await waitFor(() => expect(result.current.current).toBe(9))
    seen.length = 0
    rerender({ me: me2 })
    await act(async () => { await Promise.resolve() })
    expect(seen.length).toBeGreaterThan(0)
    expect(seen.every((x) => x.me !== 2 || (x.current === null && x.n === 0))).toBe(true)
  })

  it('laterSurvivesScreenChange', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([{ path: '/connector/proposals', body: [sum(9)] }])
    const { result, rerender } = renderHook(({ stream }) => useConnectorProposals(stream, 3, me1), { initialProps: { stream: 11 } })
    await waitFor(() => expect(result.current.current).toBe(9))
    act(() => result.current.later())
    expect(result.current.current).toBeNull()
    for (const stream of [12, -3]) {
      const n = calls.length
      rerender({ stream })
      await waitFor(() => expect(calls.length).toBeGreaterThan(n))
      await waitFor(() => expect(result.current.pending).toHaveLength(1))
      expect(result.current.current).toBeNull()
    }
    act(() => result.current.openAll())
    expect(result.current.current).toBe(9)
  })

  it('reloadsOnReconnect', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([{ path: '/connector/proposals', body: [sum(9)] }])
    const { result } = renderHook(() => useConnectorProposals(-3, 3, me1))
    await waitFor(() => expect(result.current.current).toBe(9))
    const n = listCalls(calls).length
    act(() => FakeEventSource.instances[0].emit('open'))
    await waitFor(() => expect(listCalls(calls).length).toBe(n + 1))
  })
})
