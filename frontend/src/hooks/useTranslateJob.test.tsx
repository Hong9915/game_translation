import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

const listeners: Record<string, (e: MessageEvent) => void> = {}
vi.mock('../lib/projectStream', () => ({
  subscribeProjectStream: (_id: number, l: Record<string, (e: MessageEvent) => void>) => { Object.assign(listeners, l); return () => {} },
  isProjectStreamOpen: () => true,
}))

import { useTranslateJob } from './useTranslateJob'

const send = (name: string, data: unknown) => act(() => { listeners[name]({ data: JSON.stringify(data) } as MessageEvent) })

describe('useTranslateJob', () => {
  it('follows a TRANSLATE job from progress to its summary line and refreshes the rows when done', () => {
    const qc = new QueryClient()
    const spy = vi.spyOn(qc, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    const { result } = renderHook(() => useTranslateJob(7, 7), { wrapper })
    send('snapshot', { recent: [{ id: 5, projectId: 7, kind: 'TRANSLATE', status: 'RUNNING', totalBatches: 4, doneBatches: 1, logs: [] }] })
    expect(result.current).toMatchObject({ running: true, done: 1, total: 4 })
    send('activity', { type: 'progress', jobId: 5, kind: 'TRANSLATE', status: 'RUNNING', done: 2, total: 4, line: null, at: '' })
    expect(result.current.done).toBe(2)
    send('activity', { type: 'progress', jobId: 9, kind: 'FIX_TERMS', status: 'RUNNING', done: 9, total: 9, line: null, at: '' })
    expect(result.current.done).toBe(2)
    send('activity', { type: 'log', jobId: 5, kind: 'TRANSLATE', status: 'RUNNING', done: 4, total: 4, line: 'DeepL 번역 끝: DeepL 10행 · 캐시 2행 · 메모리 0행 · 확인 필요 1행 · 보낸 글자 300자', at: '' })
    send('activity', { type: 'done', jobId: 5, kind: 'TRANSLATE', status: 'DONE', done: 4, total: 4, line: null, at: '' })
    expect(result.current.running).toBe(false)
    expect(result.current.summary).toContain('확인 필요 1행')
    expect(spy).toHaveBeenCalledWith({ queryKey: ['workspace-rows', 7] })
    act(() => result.current.dismiss())
    expect(result.current.summary).toBeNull()
  })
})
