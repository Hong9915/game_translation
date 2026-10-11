import { beforeEach, describe, expect, it } from 'vitest'
import { api } from '../api/client'
import { mockFetch } from '../test/mockFetch'
import { loadReviewer, storeReviewer } from './reviewer'

describe('reviewer identity', () => {
  beforeEach(() => localStorage.clear())

  it('round-trips through localStorage and survives garbage', () => {
    expect(loadReviewer()).toBeNull()
    storeReviewer({ id: 4, name: '지민', color: '#0c8599' })
    expect(loadReviewer()).toEqual({ id: 4, name: '지민', color: '#0c8599' })
    localStorage.setItem('gametrans.reviewer', '{broken')
    expect(loadReviewer()).toBeNull()
  })

  it('sends X-Reviewer-Id on API requests once a reviewer is stored', async () => {
    const { fn } = mockFetch([{ path: '/reviewers', body: [] }])
    await api.reviewers.list()
    expect((fn.mock.calls[0][1]?.headers as Record<string, string>)['X-Reviewer-Id']).toBeUndefined()
    storeReviewer({ id: 4, name: '지민', color: '#0c8599' })
    await api.reviewers.list()
    expect((fn.mock.calls[1][1]?.headers as Record<string, string>)['X-Reviewer-Id']).toBe('4')
  })
})
