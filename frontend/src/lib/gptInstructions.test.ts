import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

const get = vi.fn()
const setGptInstructions = vi.fn()
vi.mock('../api/client', () => ({ api: { games: { get: (...a: unknown[]) => get(...a), setGptInstructions: (...a: unknown[]) => setGptInstructions(...a) } } }))

import { useGptInstructions } from './gptInstructions'

describe('useGptInstructions', () => {
  it('saves only when changed', async () => {
    const game = { id: 3, gptTranslateBase: 'BASE', gptTranslateExtra: '존댓말', gptPolishBase: 'PB', gptPolishExtra: null }
    get.mockResolvedValue(game)
    setGptInstructions.mockResolvedValue({ ...game, gptTranslateExtra: '반말' })
    const qc = new QueryClient()
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children)
    const { result } = renderHook(() => useGptInstructions(3, 'translate'), { wrapper })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current).toMatchObject({ base: 'BASE', text: '존댓말', changed: false })
    await act(async () => { await result.current.save() })
    expect(setGptInstructions).not.toHaveBeenCalled()
    act(() => result.current.setText('반말'))
    expect(result.current.changed).toBe(true)
    await act(async () => { await result.current.save() })
    expect(setGptInstructions).toHaveBeenCalledWith(3, { translate: '반말' })
    expect(qc.getQueryData(['game', 3])).toMatchObject({ gptTranslateExtra: '반말' })
  })
})
