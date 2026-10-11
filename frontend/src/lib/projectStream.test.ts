import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isProjectStreamOpen, subscribeProjectStream } from './projectStream'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners: Record<string, ((e: MessageEvent) => void)[]> = {}
  closed = false
  url: string
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { (this.listeners[name] ??= []).push(fn) }
  removeEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners[name] = (this.listeners[name] ?? []).filter((f) => f !== fn) }
  close() { this.closed = true }
  emit(name: string, data: unknown) { for (const fn of this.listeners[name] ?? []) fn({ data: JSON.stringify(data) } as MessageEvent) }
}

class NoReadyStateEventSource {
  static instances: NoReadyStateEventSource[] = []
  url: string
  constructor(url: string) { this.url = url; NoReadyStateEventSource.instances.push(this) }
  addEventListener() { /* noop */ }
  close() { /* noop */ }
}

/** readyState를 직접 조작해 "치명적 오류(2)"와 "브라우저가 알아서 재시도 중(0)"을 흉내 낸다. */
class FlakyEventSource {
  static instances: FlakyEventSource[] = []
  listeners: Record<string, ((e: MessageEvent) => void)[]> = {}
  closed = false
  url: string
  readyState = 1
  constructor(url: string) { this.url = url; FlakyEventSource.instances.push(this) }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { (this.listeners[name] ??= []).push(fn) }
  removeEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners[name] = (this.listeners[name] ?? []).filter((f) => f !== fn) }
  close() { this.closed = true }
  emit(name: string, data: unknown = {}) { for (const fn of this.listeners[name] ?? []) fn({ data: JSON.stringify(data) } as MessageEvent) }
  fail() { this.readyState = 2; this.emit('error') }   // 치명적 오류 — 브라우저가 재시도하지 않는다
  retry() { this.readyState = 0; this.emit('error') }  // 브라우저가 알아서 재시도 중
}

describe('subscribeProjectStream', () => {
  afterEach(() => { FakeEventSource.instances = []; vi.unstubAllGlobals() })

  it('shares one EventSource per project and closes it with the last subscriber', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const a = vi.fn(), b = vi.fn()
    const offA = subscribeProjectStream(3, { activity: a })
    const offB = subscribeProjectStream(3, { review: b })
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0].url).toContain('/projects/3/jobs/events')

    FakeEventSource.instances[0].emit('review', { type: 'refresh' })
    expect(b).toHaveBeenCalledTimes(1)
    expect(a).not.toHaveBeenCalled()

    offA()
    expect(FakeEventSource.instances[0].closed).toBe(false)
    FakeEventSource.instances[0].emit('activity', {})
    expect(a).not.toHaveBeenCalled()   // 빠진 구독자는 더 받지 않는다
    offB()
    expect(FakeEventSource.instances[0].closed).toBe(true)

    subscribeProjectStream(3, { review: b })
    expect(FakeEventSource.instances).toHaveLength(2)   // 다시 구독하면 새로 연다
  })

  it('keeps projects apart', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    subscribeProjectStream(1, {}); subscribeProjectStream(2, {})
    expect(FakeEventSource.instances.map((e) => e.url)).toEqual([expect.stringContaining('/projects/1/'), expect.stringContaining('/projects/2/')])
  })
})

describe('isProjectStreamOpen', () => {
  afterEach(() => { FakeEventSource.instances = []; NoReadyStateEventSource.instances = []; vi.unstubAllGlobals() })

  it('is false when no shared stream exists for the project', () => {
    expect(isProjectStreamOpen(999)).toBe(false)
  })

  it('is false when the underlying EventSource has no readyState', () => {
    vi.stubGlobal('EventSource', NoReadyStateEventSource)
    const off = subscribeProjectStream(5, {})
    expect(isProjectStreamOpen(5)).toBe(false)
    off()
  })

  it('is true when the underlying EventSource is open (readyState 1)', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const off = subscribeProjectStream(6, {})
    const es = FakeEventSource.instances[0] as unknown as { readyState: number }
    es.readyState = 1
    expect(isProjectStreamOpen(6)).toBe(true)
    off()
  })
})

describe('reopening after a fatal error', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { FlakyEventSource.instances = []; vi.unstubAllGlobals(); vi.useRealTimers() })

  it('reopens 3s after a fatal error (readyState 2) and the original subscriber gets events from the new connection', () => {
    vi.stubGlobal('EventSource', FlakyEventSource)
    const onActivity = vi.fn()
    const off = subscribeProjectStream(10, { activity: onActivity })
    const first = FlakyEventSource.instances[0]

    first.fail()
    expect(FlakyEventSource.instances).toHaveLength(1)   // 아직 재연결 전
    vi.advanceTimersByTime(2999)
    expect(FlakyEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FlakyEventSource.instances).toHaveLength(2)
    expect(FlakyEventSource.instances[1].url).toBe(first.url)

    FlakyEventSource.instances[1].emit('activity', { real: true })   // 새 연결에서 온 이벤트가 원래 구독자에게 닿는다
    expect(onActivity).toHaveBeenCalledTimes(1)

    off()
  })

  it('does not reopen when the browser is retrying by itself (readyState 0)', () => {
    vi.stubGlobal('EventSource', FlakyEventSource)
    const off = subscribeProjectStream(11, {})
    FlakyEventSource.instances[0].retry()
    vi.advanceTimersByTime(20000)
    expect(FlakyEventSource.instances).toHaveLength(1)
    off()
  })

  it('does not reopen once the last subscriber leaves during the backoff, and closes the dead connection', () => {
    vi.stubGlobal('EventSource', FlakyEventSource)
    const off = subscribeProjectStream(12, {})
    const first = FlakyEventSource.instances[0]
    first.fail()
    vi.advanceTimersByTime(1000)   // 백오프 도중에 마지막 구독자가 빠진다
    off()
    expect(first.closed).toBe(true)
    vi.advanceTimersByTime(10000)
    expect(FlakyEventSource.instances).toHaveLength(1)   // 재연결되지 않는다
  })

  it('backs off to 10s after a second consecutive failure, then resets to 3s after a successful open', () => {
    vi.stubGlobal('EventSource', FlakyEventSource)
    const off = subscribeProjectStream(13, {})
    const first = FlakyEventSource.instances[0]
    first.fail()
    vi.advanceTimersByTime(3000)
    expect(FlakyEventSource.instances).toHaveLength(2)

    const second = FlakyEventSource.instances[1]
    second.fail()
    vi.advanceTimersByTime(9999)
    expect(FlakyEventSource.instances).toHaveLength(2)   // 10초가 아직 안 지났다
    vi.advanceTimersByTime(1)
    expect(FlakyEventSource.instances).toHaveLength(3)

    const third = FlakyEventSource.instances[2]
    third.emit('open')   // 연결 성공 — 지연이 3초로 초기화된다
    third.fail()
    vi.advanceTimersByTime(3000)
    expect(FlakyEventSource.instances).toHaveLength(4)

    off()
  })
})
