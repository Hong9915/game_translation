import { describe, expect, it, afterEach, vi } from 'vitest'
import { api } from './client'
import { mockFetch } from '../test/mockFetch'

describe('api.glossary', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('scope를 쿼리로 보낸다', async () => {
    const { calls } = mockFetch([{ path: '/games/1/glossary', body: [] }])
    await api.glossary.list(1, 'ALL')
    expect(calls[0].url).toContain('scope=ALL')
  })

  it('미리보기 건수를 부른다', async () => {
    const { calls } = mockFetch([{ path: '/games/1/glossary/apply/count', body: { count: 3 } }])
    expect(await api.glossary.count(1, { kind: 'KO', matchMode: 'PARTIAL', find: '심판', ko: '판결' })).toEqual({ count: 3 })
    expect(calls[0].url).toContain('find=%EC%8B%AC%ED%8C%90')
  })

  it('null ko는 쿼리에 보내지 않는다', async () => {
    const { calls } = mockFetch([{ path: '/games/1/glossary/apply/count', body: { count: 0 } }])
    await api.glossary.count(1, { kind: 'KO', matchMode: 'PARTIAL', find: '심판', ko: null })
    expect(calls[0].url).not.toContain('ko=')
  })

  it('항목을 삭제한다', async () => {
    const { calls } = mockFetch([{ method: 'DELETE', path: '/glossary/1', status: 204 }])
    const result = await api.glossary.remove(1)
    expect(result).toBeUndefined()
    expect(calls[0].method).toBe('DELETE')
  })
})
