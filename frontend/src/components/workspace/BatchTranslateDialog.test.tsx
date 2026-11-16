import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BatchTranslateResult, WorkspaceRow } from '../../api/types'
import { renderWithProviders } from '../../test/render'

const get = vi.fn()
const setGptInstructions = vi.fn()
const setDeepLInstructions = vi.fn()
const translateBatch = vi.fn()
vi.mock('../../api/client', () => ({
  api: {
    games: {
      get: (...a: unknown[]) => get(...a),
      setGptInstructions: (...a: unknown[]) => setGptInstructions(...a),
      setDeepLInstructions: (...a: unknown[]) => setDeepLInstructions(...a),
    },
    translateBatch: (...a: unknown[]) => translateBatch(...a),
  },
}))

import { BatchTranslateDialog } from './BatchTranslateDialog'

const game = { id: 3, name: '숲', deeplInstructions: '짧게', gptTranslateBase: '기본: 게임 문체로 번역한다.', gptTranslateExtra: '존댓말', gptPolishBase: '', gptPolishExtra: null }
const row = (id: number, over: Partial<WorkspaceRow> = {}) => ({ id, src: `原${id}`, fix: '', confirmed: false, ...over }) as WorkspaceRow
const rowsOf = (n: number) => Array.from({ length: n }, (_, i) => row(i + 1))
const result = (ids: number[], activityIds: Record<string, number>, skipped: number[] = []): BatchTranslateResult => ({
  rows: ids.filter((id) => !skipped.includes(id)).map((id) => row(id, { fix: `번역${id}` })),
  skipped: skipped.map((id) => ({ id, reason: '확정' })),
  activityIds,
})

const render = (engine: 'DEEPL' | 'GPT', rows: WorkspaceRow[], over: Record<string, unknown> = {}) => {
  const props = { onRows: vi.fn(), onTranslating: vi.fn(), onDone: vi.fn(), onClose: vi.fn(), ...over }
  renderWithProviders(<BatchTranslateDialog engine={engine} rows={rows} gameId={3} {...props} />)
  return props
}
const ids = (call: number) => (translateBatch.mock.calls[call][0] as { segmentIds: number[] }).segmentIds

beforeEach(() => {
  get.mockReset(); setGptInstructions.mockReset(); setDeepLInstructions.mockReset(); translateBatch.mockReset()
  get.mockResolvedValue(game)
})

describe('BatchTranslateDialog', () => {
  it('gpt dialog shows base read-only and saves extra before running', async () => {
    setGptInstructions.mockResolvedValue({ ...game, gptTranslateExtra: '존댓말 반말 금지' })
    translateBatch.mockImplementation(async (b: { segmentIds: number[] }) => result(b.segmentIds, { 1: 100 }))
    render('GPT', rowsOf(2))
    expect(screen.getByRole('heading', { name: 'GPT 번역 (2행)' })).toBeInTheDocument()
    expect(await screen.findByText('기본: 게임 문체로 번역한다.')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('기본: 게임 문체로 번역한다.')).toBeNull()   // 고칠 수 있는 칸이 아니다
    const extra = screen.getByRole('textbox', { name: '덧붙일 지시' })
    await waitFor(() => expect(extra).toHaveValue('존댓말'))
    await userEvent.type(extra, ' 반말 금지')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    await waitFor(() => expect(translateBatch).toHaveBeenCalledTimes(1))
    expect(setGptInstructions).toHaveBeenCalledWith(3, { translate: '존댓말 반말 금지' })
    expect(setGptInstructions.mock.invocationCallOrder[0]).toBeLessThan(translateBatch.mock.invocationCallOrder[0])
    expect(translateBatch.mock.calls[0][0]).toMatchObject({ engine: 'GPT', segmentIds: [1, 2] })
  })

  it('sends chunks of 20 for GPT and 50 for DeepL passing activityIds forward', async () => {
    let n = 0
    translateBatch.mockImplementation(async (b: { segmentIds: number[] }) => { n += 1; return result(b.segmentIds, { 1: 100, [n + 1]: 200 + n }) })
    const gpt = render('GPT', rowsOf(45))
    await screen.findByText('기본: 게임 문체로 번역한다.')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    await waitFor(() => expect(gpt.onDone).toHaveBeenCalled())
    expect([0, 1, 2].map((c) => ids(c).length)).toEqual([20, 20, 5])
    expect(translateBatch.mock.calls[0][0].activityIds).toBeUndefined()
    expect(translateBatch.mock.calls[1][0].activityIds).toEqual({ 1: 100, 2: 201 })
    expect(translateBatch.mock.calls[2][0].activityIds).toEqual({ 1: 100, 3: 202 })
  })

  it('sends chunks of 50 for DeepL', async () => {
    translateBatch.mockImplementation(async (b: { segmentIds: number[] }) => result(b.segmentIds, { 1: 100 }))
    const deepl = render('DEEPL', rowsOf(120))
    expect(await screen.findByRole('textbox', { name: 'DeepL 지시문' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    await waitFor(() => expect(deepl.onDone).toHaveBeenCalled())
    expect([0, 1, 2].map((c) => ids(c).length)).toEqual([50, 50, 20])
    expect(translateBatch.mock.calls[0][0]).toMatchObject({ engine: 'DEEPL' })
    expect(translateBatch.mock.calls[1][0].activityIds).toEqual({ 1: 100 })
  })

  it('cancel stops after current chunk', async () => {
    let release: (v: BatchTranslateResult) => void = () => {}
    translateBatch.mockImplementationOnce((b: { segmentIds: number[] }) => new Promise((res) => { release = () => res(result(b.segmentIds, { 1: 100 })) }))
    const props = render('GPT', rowsOf(45))
    await screen.findByText('기본: 게임 문체로 번역한다.')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText('0/45행')).toBeInTheDocument()
    expect(props.onTranslating).toHaveBeenCalledWith(ids(0), true)
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    release(result(ids(0), { 1: 100 }))
    await waitFor(() => expect(props.onDone).toHaveBeenCalledWith({ activityIds: [100], translated: 20, skipped: 0 }))
    expect(translateBatch).toHaveBeenCalledTimes(1)
    expect(props.onTranslating).toHaveBeenCalledWith(ids(0), false)
    expect(props.onClose).toHaveBeenCalled()
  })

  it('cmd+Enter does nothing while instructions are loading', async () => {
    get.mockReturnValue(new Promise(() => {}))
    translateBatch.mockImplementation(async (b: { segmentIds: number[] }) => result(b.segmentIds, { 1: 100 }))
    const props = render('GPT', rowsOf(2))
    expect(screen.getByRole('button', { name: '번역' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Enter', metaKey: true })
    await new Promise((r) => setTimeout(r, 20))
    expect(translateBatch).not.toHaveBeenCalled()
    expect(props.onDone).not.toHaveBeenCalled()
  })

  it('cmd+Enter does nothing while DeepL instructions are over the limit', async () => {
    const props = render('DEEPL', rowsOf(2))
    const field = await screen.findByRole('textbox', { name: 'DeepL 지시문' })
    await waitFor(() => expect(field).toHaveValue('짧게'))
    fireEvent.change(field, { target: { value: Array.from({ length: 11 }, (_, i) => `줄${i}`).join('\n') } })
    expect(screen.getByRole('button', { name: '번역' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Enter', metaKey: true })
    await new Promise((r) => setTimeout(r, 20))
    // 칸 아래 한도 안내 하나뿐 — 실행을 시도해 생기는 오류 줄은 없다
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(translateBatch).not.toHaveBeenCalled()
    expect(props.onDone).not.toHaveBeenCalled()
  })

  it('Esc while running cancels instead of closing at once', async () => {
    let release: (v: BatchTranslateResult) => void = () => {}
    translateBatch.mockImplementationOnce((b: { segmentIds: number[] }) => new Promise((res) => { release = () => res(result(b.segmentIds, { 1: 100 })) }))
    const props = render('GPT', rowsOf(25))
    await screen.findByText('기본: 게임 문체로 번역한다.')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Enter', metaKey: true })
    await screen.findByText('0/25행')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(props.onClose).not.toHaveBeenCalled()
    release(result(ids(0), { 1: 100 }))
    await waitFor(() => expect(props.onClose).toHaveBeenCalled())
    expect(translateBatch).toHaveBeenCalledTimes(1)
  })

  it('reports activity ids on done', async () => {
    translateBatch
      .mockResolvedValueOnce(result(Array.from({ length: 20 }, (_, i) => i + 1), { 1: 100 }, [3]))
      .mockResolvedValueOnce(result([21, 22], { 1: 100, 2: 200 }))
    const props = render('GPT', rowsOf(22))
    await screen.findByText('기본: 게임 문체로 번역한다.')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    await waitFor(() => expect(props.onDone).toHaveBeenCalledWith({ activityIds: [100, 200], translated: 21, skipped: 1 }))
    expect(props.onRows).toHaveBeenCalledTimes(2)
    expect(props.onRows.mock.calls[1][0]).toHaveLength(2)
    expect(props.onClose).toHaveBeenCalled()
  })

  it('on error shows the message and still reports what finished', async () => {
    translateBatch
      .mockResolvedValueOnce(result(Array.from({ length: 20 }, (_, i) => i + 1), { 1: 100 }))
      .mockRejectedValueOnce(new Error('GPT 응답이 없습니다'))
    const props = render('GPT', rowsOf(30))
    await screen.findByText('기본: 게임 문체로 번역한다.')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText('GPT 응답이 없습니다')).toBeInTheDocument()
    expect(props.onDone).toHaveBeenCalledWith({ activityIds: [100], translated: 20, skipped: 0 })
    expect(props.onClose).not.toHaveBeenCalled()
  })

  it('skips confirmed rows count shown', async () => {
    translateBatch.mockImplementation(async (b: { segmentIds: number[] }) => result(b.segmentIds, { 1: 100 }))
    const props = render('DEEPL', [row(1), row(2, { confirmed: true }), row(3, { confirmed: true }), row(4)])
    expect(screen.getByText('확정 2행은 건너뜁니다')).toBeInTheDocument()
    await screen.findByRole('textbox', { name: 'DeepL 지시문' })
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    await waitFor(() => expect(props.onDone).toHaveBeenCalledWith({ activityIds: [100], translated: 2, skipped: 2 }))
    expect(ids(0)).toEqual([1, 4])
  })
})
