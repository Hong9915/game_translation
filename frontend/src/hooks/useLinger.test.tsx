import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLinger } from './useLinger'

describe('useLinger', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('값이 null이 돼도 ms 동안은 마지막 값을 보여 주고, 지나면 null이 된다', () => {
    const { result, rerender } = renderHook(({ v }) => useLinger(v, 200), { initialProps: { v: 'a' as string | null } })
    expect(result.current.shown).toBe('a')
    rerender({ v: null })
    expect(result.current.shown).toBe('a')
    act(() => { vi.advanceTimersByTime(199) })
    expect(result.current.shown).toBe('a')
    act(() => { vi.advanceTimersByTime(1) })
    expect(result.current.shown).toBeNull()
  })

  it('닫히는 동안 다시 열면 남아 있던 값을 버리지 않고 seq만 올린다 — 새로 마운트하는 key', () => {
    const { result, rerender } = renderHook(({ v }) => useLinger(v, 200), { initialProps: { v: 'a' as string | null } })
    const seq0 = result.current.seq
    rerender({ v: null })
    rerender({ v: 'a' })
    expect(result.current.shown).toBe('a')
    expect(result.current.seq).toBe(seq0 + 1)
    // 다시 열린 뒤에는 옛 타이머가 값을 지우지 않는다
    act(() => { vi.advanceTimersByTime(500) })
    expect(result.current.shown).toBe('a')
  })

  it('열린 채로 다른 값으로 바꾸면 seq는 그대로다(컴포넌트가 달라 어차피 새로 마운트된다)', () => {
    const { result, rerender } = renderHook(({ v }) => useLinger(v, 200), { initialProps: { v: 'a' as string | null } })
    const seq0 = result.current.seq
    rerender({ v: 'b' })
    expect(result.current.shown).toBe('b')
    expect(result.current.seq).toBe(seq0)
  })
})
