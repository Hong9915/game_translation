import { describe, it, expect, vi } from 'vitest'
import { runChunks } from './chunkRun'

const ids = Array.from({ length: 45 }, (_, i) => i + 1)

describe('runChunks', () => {
  it('45개·size 20이면 20,20,5로 세 번 부른다', async () => {
    const sizes: number[] = []
    const onProgress = vi.fn()
    const r = await runChunks(ids, 20, async c => { sizes.push(c.length); return c.length }, { onProgress })
    expect(sizes).toEqual([20, 20, 5])
    expect(r).toEqual({ results: [20, 20, 5], cancelled: false })
    expect(onProgress.mock.calls).toEqual([[20, 45], [40, 45], [45, 45]])
  })

  it('두 번째 뒤 abort하면 멈춘다', async () => {
    const ac = new AbortController()
    const onProgress = vi.fn((done: number) => { if (done === 40) ac.abort() })
    const step = vi.fn(async (c: number[]) => c.length)
    const r = await runChunks(ids, 20, step, { signal: ac.signal, onProgress })
    expect(step).toHaveBeenCalledTimes(2)
    expect(r.cancelled).toBe(true)
    expect(r.results).toHaveLength(2)
    expect(onProgress.mock.calls).toEqual([[20, 45], [40, 45]])
  })

  it('step이 던지면 그대로 reject', async () => {
    await expect(runChunks(ids, 20, async () => { throw new Error('boom') }, {})).rejects.toThrow('boom')
  })
})
