import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { usePresence } from './usePresence'

const me = { id: 1, name: '형준', color: '#1f4b99' }
const jimin = { id: 2, name: '지민', color: '#0c8599' }

describe('usePresence', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); localStorage.clear() })

  it('touches presence on mount, debounces a cell change by 300ms, heartbeats every 25s, then leaves on unmount', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/projects/5/presence', body: { reviewers: [jimin] } },
      { method: 'DELETE', path: '/projects/5/presence', status: 204 },
    ])
    const onSnapshot = vi.fn()
    const { rerender, unmount } = renderHook(({ editing }) => usePresence(5, me, editing, onSnapshot), { initialProps: { editing: null as number | null } })

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ sheet: null, editingSegmentId: null })
    await vi.waitFor(() => expect(onSnapshot).toHaveBeenCalledWith([jimin]))

    rerender({ editing: 42 })
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)   // 300ms이 지나기 전에는 아직 안 나간다
    await vi.advanceTimersByTimeAsync(300)
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2)
    expect(calls.filter((c) => c.method === 'POST').at(-1)?.body).toEqual({ sheet: null, editingSegmentId: 42 })

    await vi.advanceTimersByTimeAsync(25_000)
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(3)

    unmount()
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true))
  })

  it('debounces rapid cell changes so only the final value is posted once', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/projects/5/presence', body: { reviewers: [] } },
      { method: 'DELETE', path: '/projects/5/presence', status: 204 },
    ])
    const { rerender, unmount } = renderHook(({ editing }) => usePresence(5, me, editing, vi.fn()), { initialProps: { editing: null as number | null } })
    await vi.advanceTimersByTimeAsync(0)
    const posts = () => calls.filter((c) => c.method === 'POST')
    const afterMount = posts().length   // 마운트 시 즉시 1번

    rerender({ editing: 9 })
    await vi.advanceTimersByTimeAsync(100)   // 300ms이 되기 전에 다시 바뀐다(blur 뒤 바로 focus)
    rerender({ editing: 42 })
    await vi.advanceTimersByTimeAsync(299)
    expect(posts()).toHaveLength(afterMount)   // 아직 debounce 중 — 9에 대한 POST도 나가지 않았다
    await vi.advanceTimersByTimeAsync(1)
    expect(posts()).toHaveLength(afterMount + 1)   // 마지막 값(42) 하나만 나간다
    expect(posts().at(-1)?.body).toEqual({ sheet: null, editingSegmentId: 42 })

    unmount()
  })

  it('leaves under the OLD reviewer id when identity switches, and sends no DELETE on a mere rename (same id)', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/projects/5/presence', body: { reviewers: [] } },
      { method: 'DELETE', path: '/projects/5/presence', status: 204 },
    ])
    localStorage.setItem('gametrans.reviewer', JSON.stringify(me))
    const { rerender, unmount } = renderHook(({ reviewer }) => usePresence(5, reviewer, null, vi.fn()), { initialProps: { reviewer: me } })
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))

    // 이름만 바뀐 rename(같은 id)은 DELETE를 보내면 안 된다.
    const renamed = { ...me, name: '형준改' }
    localStorage.setItem('gametrans.reviewer', JSON.stringify(renamed))
    rerender({ reviewer: renamed })
    await vi.advanceTimersByTimeAsync(0)
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    // 검토자 자체가 바뀌는 identity switch(다른 id)는 옛 id로 DELETE, 새 id로 POST를 보내야 한다 —
    // request()가 호출 시점에 저장된 검토자를 읽으므로, 명시하지 않으면 DELETE도 새 id로 나가 버린다.
    localStorage.setItem('gametrans.reviewer', JSON.stringify(jimin))
    rerender({ reviewer: jimin })
    await vi.advanceTimersByTimeAsync(0)

    const del = calls.find((c) => c.method === 'DELETE')
    expect(del?.headers?.['X-Reviewer-Id']).toBe(String(me.id))
    const lastPost = calls.filter((c) => c.method === 'POST').at(-1)
    expect(lastPost?.headers?.['X-Reviewer-Id']).toBe(String(jimin.id))

    unmount()
  })

  // 탭을 닫거나 다른 주소로 넘어갈 때는 React의 cleanup이 돌지 않는다 — pagehide에서 보내야
  // keepalive DELETE가 제 역할을 하고, 남은 사람들 화면에서 내 아바타가 60초 동안 남지 않는다.
  it('leaves once on pagehide with the captured id, and stops listening after unmount', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/projects/5/presence', body: { reviewers: [] } },
      { method: 'DELETE', path: '/projects/5/presence', status: 204 },
    ])
    const { unmount } = renderHook(() => usePresence(5, me, null, vi.fn()))
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))

    window.dispatchEvent(new Event('pagehide'))
    await vi.advanceTimersByTimeAsync(0)
    const deletes = () => calls.filter((c) => c.method === 'DELETE')
    expect(deletes()).toHaveLength(1)
    expect(deletes()[0].headers?.['X-Reviewer-Id']).toBe(String(me.id))

    // 언마운트는 cleanup으로 한 번 더 보내고(정상 이탈 경로), 그 뒤 리스너는 떼어져 있어야 한다.
    unmount()
    await vi.advanceTimersByTimeAsync(0)
    const afterUnmount = deletes().length
    window.dispatchEvent(new Event('pagehide'))
    await vi.advanceTimersByTimeAsync(0)
    expect(deletes()).toHaveLength(afterUnmount)
  })

  it('sends nothing when there is no stored reviewer', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/projects/5/presence', body: { reviewers: [] } },
      { method: 'DELETE', path: '/projects/5/presence', status: 204 },
    ])
    const { unmount } = renderHook(() => usePresence(5, null, null, vi.fn()))
    await vi.advanceTimersByTimeAsync(30_000)
    unmount()
    expect(calls).toHaveLength(0)
  })

  it('sends the active sheet in the presence body, and re-announces (debounced) when the sheet changes', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/projects/5/presence', body: { reviewers: [] } },
      { method: 'DELETE', path: '/projects/5/presence', status: 204 },
    ])
    const posts = () => calls.filter((c) => c.method === 'POST')
    const { rerender, unmount } = renderHook(
      ({ sheet }) => usePresence(5, me, null, vi.fn(), sheet),
      { initialProps: { sheet: null as string | null } },
    )
    await vi.waitFor(() => expect(posts().length).toBeGreaterThan(0))
    expect(posts()[0].body).toEqual({ sheet: null, editingSegmentId: null })

    rerender({ sheet: 'Skill' })
    expect(posts()).toHaveLength(1)   // 300ms 지나기 전에는 아직
    await vi.advanceTimersByTimeAsync(300)
    expect(posts()).toHaveLength(2)
    expect(posts().at(-1)?.body).toEqual({ sheet: 'Skill', editingSegmentId: null })

    // 하트비트도 최신 시트를 그대로 보낸다(간격 자체는 다시 시작하지 않는다).
    await vi.advanceTimersByTimeAsync(25_000)
    expect(posts().at(-1)?.body).toEqual({ sheet: 'Skill', editingSegmentId: null })

    unmount()
  })
})
