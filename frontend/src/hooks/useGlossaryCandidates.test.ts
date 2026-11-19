import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement, type ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { useGlossaryCandidates } from './useGlossaryCandidates'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners: Record<string, ((e: MessageEvent) => void)[]> = {}
  url: string
  readyState = 0
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { (this.listeners[name] ??= []).push(fn) }
  removeEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners[name] = (this.listeners[name] ?? []).filter((f) => f !== fn) }
  close() {}
  emit(name: string, data: unknown = {}) { for (const fn of this.listeners[name] ?? []) fn({ data: JSON.stringify(data) } as MessageEvent) }
}

const cand = (id: number) => ({ id, gameId: 3, src: '关卡', ko: '스테이지', oldKo: null, rows: 2, reason: null, source: 'CONNECTOR', status: 'PENDING',
  createdBy: 1, createdByName: '형준', createdAt: '2026-11-18T00:00:00Z', conflictKo: null })
const wrapper = ({ children }: { children: ReactNode }) =>
  createElement(QueryClientProvider, { client: new QueryClient({ defaultOptions: { queries: { retry: false } } }) }, children)
const listCalls = (calls: { url: string }[]) => calls.filter((c) => c.url.includes('/glossary/candidates'))

describe('useGlossaryCandidates', () => {
  afterEach(() => { FakeEventSource.instances = []; vi.unstubAllGlobals() })

  it('loadsAndReloadsOnGlossaryCandidateEvent', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([{ path: '/games/3/glossary/candidates', body: [cand(1)] }])
    const { result } = renderHook(() => useGlossaryCandidates(-3, 3), { wrapper })
    await waitFor(() => expect(result.current.list).toHaveLength(1))
    const n = listCalls(calls).length
    act(() => FakeEventSource.instances[0].emit('glossary-candidate', { type: 'glossary-candidate', gameId: 9, candidateId: 5, status: 'PENDING' }))
    await act(async () => { await Promise.resolve() })
    expect(listCalls(calls).length).toBe(n)
    act(() => FakeEventSource.instances[0].emit('glossary-candidate', { type: 'glossary-candidate', gameId: 3, candidateId: 5, status: 'PENDING' }))
    await waitFor(() => expect(listCalls(calls).length).toBe(n + 1))
  })

  it('reloadsOnReconnect', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { calls } = mockFetch([{ path: '/games/3/glossary/candidates', body: [cand(1)] }])
    const { result } = renderHook(() => useGlossaryCandidates(-3, 3), { wrapper })
    await waitFor(() => expect(result.current.list).toHaveLength(1))
    const n = listCalls(calls).length
    act(() => FakeEventSource.instances[0].emit('open'))
    await waitFor(() => expect(listCalls(calls).length).toBe(n + 1))
  })

  it('failureIsEmptyList', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    mockFetch([{ path: '/games/3/glossary/candidates', status: 500, body: { message: 'x' } }])
    const { result } = renderHook(() => useGlossaryCandidates(-3, 3), { wrapper })
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(result.current.list).toEqual([])
  })
})
