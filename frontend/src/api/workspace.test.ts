import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { api } from './client'

describe('api.workspace', () => {
  it('builds the rows URL with sheet, paging, filter and search', async () => {
    const { calls } = mockFetch([{ path: '/review/7/rows', body: { rows: [], page: 0, size: 500, total: 0 } }])
    await api.workspace.rows(7, { sheet: 'UI 텍스트', filter: 'CHANGED', q: '성물' })
    expect(calls[0].url).toContain('/review/7/rows?sheet=UI+%ED%85%8D%EC%8A%A4%ED%8A%B8&page=0&size=500&filter=CHANGED&q=%EC%84%B1%EB%AC%BC')
  })

  it('omits an empty search and defaults the filter', async () => {
    const { calls } = mockFetch([{ path: '/review/7/rows', body: { rows: [], page: 2, size: 500, total: 0 } }])
    await api.workspace.rows(7, { sheet: 'UI', page: 2, q: '' })
    expect(calls[0].url).toContain('/review/7/rows?sheet=UI&page=2&size=500')
    expect(calls[0].url).not.toContain('q=')
    expect(calls[0].url).not.toContain('filter=')
  })

  it('requests the summary for one sheet or the whole project', async () => {
    const { calls } = mockFetch([{ path: '/review/7/rows/summary', body: { sheets: [], total: 0, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, unassigned: 0, reviewers: [] } }])
    await api.workspace.summary(7, 'UI')
    await api.workspace.summary(7)
    expect(calls[0].url).toContain('/review/7/rows/summary?sheet=UI')
    expect(calls[1].url).toMatch(/\/review\/7\/rows\/summary$/)
  })
})
