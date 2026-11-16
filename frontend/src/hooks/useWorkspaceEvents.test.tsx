import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkspaceRowsResponse } from '../api/types'
import { useWorkspaceEvents } from './useWorkspaceEvents'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners: Record<string, ((e: MessageEvent) => void)[]> = {}
  closed = false
  url: string
  readyState = 0
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { (this.listeners[name] ??= []).push(fn) }
  removeEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners[name] = (this.listeners[name] ?? []).filter((f) => f !== fn) }
  close() {}
  emit(name: string, data: unknown = {}) { for (const fn of this.listeners[name] ?? []) fn({ data: JSON.stringify(data) } as MessageEvent) }
}

const me = { id: 1, name: '형준', color: '#1f4b99' }
const key = ['workspace-rows', 5, 'UI', 'ALL', '', 0]
const summaryKey = ['workspace-summary', 5, 'UI']
const row = { id: 3, rowNo: 4, pairIndex: 0, indexLabel: 'UI!4', keyText: 'K', src: '确认', en: null, existingKo: '확인', fix: '확인', changed: false, termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, review3: false, needsCheck: false, confirmed: false, finalReview: false, finalMark: false, note: null, lastEditor: null, lastEditedAt: null, history: [], dupCount: 0 }
const seg = { id: 3, indexLabel: 'UI!4', srcText: '确认', enText: null, currentText: '확인함', lenKo: 3, lenSrc: 2, flagged: false, history: [] }

function setup() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  qc.setQueryData(key, { rows: [row], page: 0, size: 500, total: 1 } satisfies WorkspaceRowsResponse)
  qc.setQueryData(summaryKey, { sheets: [], total: 1, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, unassigned: 1, reviewers: [] })
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  return { qc, wrapper }
}

describe('useWorkspaceEvents', () => {
  afterEach(() => { FakeEventSource.instances = []; vi.unstubAllGlobals(); vi.useRealTimers() })

  it('patches the cached row, highlights it for 2s and invalidates the summary once', () => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    const { qc, wrapper } = setup()
    const { result } = renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    const es = FakeEventSource.instances[0]
    act(() => es.emit('review', { type: 'segment-updated', by: 7, row: seg }))
    expect((qc.getQueryData(key) as WorkspaceRowsResponse).rows[0].fix).toBe('확인함')
    expect(result.current.highlighted.has(3)).toBe(true)
    expect(qc.getQueryState(summaryKey)?.isInvalidated).toBe(false)
    act(() => { vi.advanceTimersByTime(500) })
    expect(qc.getQueryState(summaryKey)?.isInvalidated).toBe(true)
    act(() => { vi.advanceTimersByTime(1500) })
    expect(result.current.highlighted.has(3)).toBe(false)
  })

  it('does not highlight my own change and tracks presence without me', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = setup()
    const { result } = renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    const es = FakeEventSource.instances[0]
    act(() => es.emit('review', { type: 'segment-updated', by: 1, row: seg }))
    expect(result.current.highlighted.size).toBe(0)
    act(() => es.emit('review', { type: 'presence', reviewers: [{ ...me, sheet: 'UI', editingSegmentId: null }, { id: 7, name: '지민', color: '#0c8599', sheet: 'UI', editingSegmentId: null }] }))
    expect(result.current.presence.map((p) => p.name)).toEqual(['지민'])
  })

  it('invalidates rows, summary and reviewers 500ms after refresh', () => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    const { qc, wrapper } = setup()
    qc.setQueryData(['reviewers'], [])
    renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    act(() => FakeEventSource.instances[0].emit('review', { type: 'refresh', by: null }))
    expect(qc.getQueryState(key)?.isInvalidated).toBe(false)
    act(() => { vi.advanceTimersByTime(500) })
    expect(qc.getQueryState(['reviewers'])?.isInvalidated).toBe(true)
    expect(qc.getQueryState(key)?.isInvalidated).toBe(true)
    expect(qc.getQueryState(summaryKey)?.isInvalidated).toBe(true)
  })

  it('patches marks and flashes the rows; marks-bulk reloads rows and summary', () => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    const { qc, wrapper } = setup()
    const { result } = renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    act(() => FakeEventSource.instances[0].emit('review', { type: 'marks', by: 7, segmentIds: [3], review1: true, review2: true, needsCheck: null, note: null, noteChanged: false }))
    expect(qc.getQueryData<WorkspaceRowsResponse>(key)?.rows[0]).toMatchObject({ review1: true, review2: true })
    expect(result.current.highlighted.has(3)).toBe(true)
    act(() => FakeEventSource.instances[0].emit('review', { type: 'marks-bulk', by: 7, sheet: 'UI' }))
    act(() => { vi.advanceTimersByTime(500) })
    expect(qc.getQueryState(key)?.isInvalidated).toBe(true)
    expect(qc.getQueryState(summaryKey)?.isInvalidated).toBe(true)
  })

  it('게임 화면에 파일마다 온 marks-bulk·refresh 묶음은 500ms 안에 목록·요약을 한 번만 다시 읽는다', () => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    const { qc, wrapper } = setup()
    const spy = vi.spyOn(qc, 'invalidateQueries')
    renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    const es = FakeEventSource.instances[0]
    const rowsCalls = () => spy.mock.calls.filter(([f]) => (f as { queryKey?: unknown[] })?.queryKey?.[0] === 'workspace-rows').length
    act(() => { for (let i = 0; i < 41; i++) es.emit('review', i % 2 ? { type: 'refresh', by: null } : { type: 'marks-bulk', by: 7, sheet: null }) })
    expect(rowsCalls()).toBe(0)
    act(() => { vi.advanceTimersByTime(500) })
    expect(rowsCalls()).toBe(1)
    expect(qc.getQueryState(summaryKey)?.isInvalidated).toBe(true)
  })

  it('invalidates glossary lists on glossary event', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { qc, wrapper } = setup()
    qc.setQueryData(['glossary', 3, 'GAME'], [])
    renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    act(() => FakeEventSource.instances[0].emit('review', { type: 'glossary', by: 7 }))
    expect(qc.getQueryState(['glossary', 3, 'GAME'])?.isInvalidated).toBe(true)
    expect(qc.getQueryState(key)?.isInvalidated).toBe(false)
  })

  it('segment-updated·refresh·glossary가 오면 변경 내역 쿼리를 500ms 병합으로 무효화한다', () => {
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    const { qc, wrapper } = setup()
    const activityKey = ['activity', 5]
    qc.setQueryData(activityKey, { items: [] })
    renderHook(() => useWorkspaceEvents(5, me), { wrapper })
    const es = FakeEventSource.instances[0]
    for (const ev of [{ type: 'refresh', by: null }, { type: 'glossary', by: null }, { type: 'segment-updated', by: 7, row: seg }]) {
      act(() => es.emit('review', ev))
      expect(qc.getQueryState(activityKey)?.isInvalidated).toBe(false)
      act(() => { vi.advanceTimersByTime(500) })
      expect(qc.getQueryState(activityKey)?.isInvalidated).toBe(true)
      qc.setQueryData(activityKey, { items: [] })   // 다음 이벤트를 위해 무효화 상태를 되돌린다
    }
  })
})
