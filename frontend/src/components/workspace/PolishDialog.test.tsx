import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PolishResult, WorkspaceRow } from '../../api/types'
import { renderWithProviders } from '../../test/render'

const get = vi.fn()
const setGptInstructions = vi.fn()
const polish = vi.fn()
const saveBatch = vi.fn()
vi.mock('../../api/client', () => ({
  api: {
    games: {
      get: (...a: unknown[]) => get(...a),
      setGptInstructions: (...a: unknown[]) => setGptInstructions(...a),
    },
    polish: (...a: unknown[]) => polish(...a),
    saveBatch: (...a: unknown[]) => saveBatch(...a),
  },
}))

import { PolishDialog } from './PolishDialog'

const game = { id: 3, name: '숲', deeplInstructions: '', gptTranslateBase: '', gptTranslateExtra: null, gptPolishBase: '기본: 어색한 곳만 다듬는다.', gptPolishExtra: '존댓말' }
const row = (id: number, over: Partial<WorkspaceRow> = {}) => ({ id, rowNo: id, src: `原${id}`, fix: `고침${id}`, confirmed: false, dupCount: 0, ...over }) as WorkspaceRow
const rowsOf = (n: number) => Array.from({ length: n }, (_, i) => row(i + 1))
/** proposeIds만 제안, 나머지는 고칠 데 없음. */
const result = (ids: number[], proposeIds: number[] = ids): PolishResult => ({
  items: ids.filter((id) => proposeIds.includes(id)).map((id) => ({ segmentId: id, before: `고침${id}`, after: `다듬음${id}`, reason: `까닭${id}` })),
  kept: ids.filter((id) => !proposeIds.includes(id)).length,
  skipped: [],
})

const render = (rows: WorkspaceRow[]) => {
  const props = { onDone: vi.fn(), onClose: vi.fn() }
  renderWithProviders(<PolishDialog rows={rows} gameId={3} {...props} />)
  return props
}
const start = async () => {
  await screen.findByText('기본: 어색한 곳만 다듬는다.')
  await userEvent.click(screen.getByRole('button', { name: '다듬기' }))
}

beforeEach(() => {
  get.mockReset(); setGptInstructions.mockReset(); polish.mockReset(); saveBatch.mockReset()
  get.mockResolvedValue(game)
  saveBatch.mockResolvedValue({ saved: 0, skipped: 0, propagated: 0, activityIds: [] })
})

describe('PolishDialog', () => {
  it('runs polish in chunks and lists only proposals', async () => {
    polish.mockImplementation(async (ids: number[]) => result(ids, [2, 25]))
    render(rowsOf(45))
    expect(screen.getByRole('heading', { name: 'GPT로 다듬기 (45행)' })).toBeInTheDocument()
    await start()
    expect(await screen.findByText('다듬음2')).toBeInTheDocument()
    expect(polish.mock.calls.map((c) => (c[0] as number[]).length)).toEqual([20, 20, 5])
    expect(screen.getByText('다듬음25')).toBeInTheDocument()
    expect(screen.queryByText('다듬음1')).toBeNull()
    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    expect(screen.getByText('原2')).toBeInTheDocument()
    expect(screen.getByText('고침2')).toBeInTheDocument()
    expect(screen.getByText('까닭2')).toBeInTheDocument()
    expect(screen.getByText('고칠 데 없음 43행')).toBeInTheDocument()
  })

  it('skips confirmed rows and shows skipped count', async () => {
    polish.mockResolvedValue({ items: [{ segmentId: 1, before: '고침1', after: '다듬음1', reason: null }], kept: 0, skipped: [{ id: 3, reason: 'Fix 없음' }] })
    render([row(1), row(2, { confirmed: true }), row(3)])
    await start()
    await screen.findByText('다듬음1')
    expect(polish).toHaveBeenCalledWith([1, 3])
    expect(screen.getByText('건너뜀 2행(확정·Fix 없음·자리표시자)')).toBeInTheDocument()
  })

  it('unchecked rows are not saved', async () => {
    polish.mockImplementation(async (ids: number[]) => result(ids))
    render(rowsOf(3))
    await start()
    await screen.findByText('다듬음2')
    await userEvent.click(screen.getByRole('checkbox', { name: '2행 넣기' }))
    await userEvent.click(screen.getByRole('button', { name: '넣기 (2행)' }))
    await waitFor(() => expect(saveBatch).toHaveBeenCalledTimes(1))
    expect((saveBatch.mock.calls[0][0] as { segmentId: number }[]).map((i) => i.segmentId)).toEqual([1, 3])
  })

  it('sends base for change detection', async () => {
    polish.mockResolvedValue({ items: [{ segmentId: 7, before: '받을 때 Fix', after: '다듬은 글', reason: null }], kept: 0, skipped: [] })
    render([row(7, { fix: '지금 화면 Fix' })])
    await start()
    await screen.findByText('다듬은 글')
    await userEvent.click(screen.getByRole('button', { name: '넣기 (1행)' }))
    await waitFor(() => expect(saveBatch).toHaveBeenCalledWith([{ segmentId: 7, text: '다듬은 글', base: '받을 때 Fix' }]))
  })

  it('shows same-source count from dupCount without double counting', async () => {
    // 1·2는 원문이 같아(같은 원문 4행) 한 번만 센다: 4-1=3. 3은 2행 중 하나라 +1. 4는 홀로라 0.
    const rows = [row(1, { src: '甲', dupCount: 4 }), row(2, { src: '甲', dupCount: 4 }), row(3, { src: '乙', dupCount: 2 }), row(4, { src: '丙', dupCount: 0 })]
    polish.mockImplementation(async (ids: number[]) => result(ids))
    render(rows)
    await start()
    await screen.findByText('다듬음1')
    expect(screen.getByText('같은 원문 4행에도 들어갑니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: '1행 넣기' }))
    expect(screen.getByText('같은 원문 4행에도 들어갑니다')).toBeInTheDocument()   // 2가 아직 甲을 고른다
    await userEvent.click(screen.getByRole('checkbox', { name: '2행 넣기' }))
    expect(screen.getByText('같은 원문 1행에도 들어갑니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: '3행 넣기' }))
    expect(screen.queryByText(/같은 원문/)).toBeNull()
  })

  it('no proposals shows nothing to fix', async () => {
    polish.mockImplementation(async (ids: number[]) => result(ids, []))
    const props = render(rowsOf(2))
    await start()
    expect(await screen.findByText('고칠 데가 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /넣기/ })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(props.onClose).toHaveBeenCalled()
    expect(saveBatch).not.toHaveBeenCalled()
  })

  it('cancel goes to review with what was received', async () => {
    let release = () => {}
    polish.mockImplementationOnce((ids: number[]) => new Promise((res) => { release = () => res(result(ids, [1])) }))
    const props = render(rowsOf(45))
    await start()
    expect(await screen.findByText('0/45행')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(props.onClose).not.toHaveBeenCalled()
    release()
    expect(await screen.findByText('다듬음1')).toBeInTheDocument()
    expect(polish).toHaveBeenCalledTimes(1)
  })

  it('saves instructions before polishing', async () => {
    setGptInstructions.mockResolvedValue({ ...game, gptPolishExtra: '존댓말 반말 금지' })
    polish.mockImplementation(async (ids: number[]) => result(ids, []))
    render(rowsOf(1))
    const extra = screen.getByRole('textbox', { name: '덧붙일 지시' })
    await waitFor(() => expect(extra).toHaveValue('존댓말'))
    await userEvent.type(extra, ' 반말 금지')
    await start()
    await waitFor(() => expect(polish).toHaveBeenCalled())
    expect(setGptInstructions).toHaveBeenCalledWith(3, { polish: '존댓말 반말 금지' })
    expect(setGptInstructions.mock.invocationCallOrder[0]).toBeLessThan(polish.mock.invocationCallOrder[0])
  })

  it('cmd+Enter does nothing while instructions are loading', async () => {
    get.mockReturnValue(new Promise(() => {}))
    polish.mockImplementation(async (ids: number[]) => result(ids))
    render(rowsOf(2))
    expect(screen.getByRole('button', { name: '다듬기' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Enter', metaKey: true })
    await new Promise((r) => setTimeout(r, 20))
    expect(polish).not.toHaveBeenCalled()
    expect(screen.queryByText('0/2행')).toBeNull()
  })

  it('reports activity ids on done', async () => {
    polish.mockImplementation(async (ids: number[]) => result(ids))
    saveBatch.mockResolvedValue({ saved: 2, skipped: 1, propagated: 4, activityIds: [500, 501] })
    const props = render(rowsOf(3))
    await start()
    await screen.findByText('다듬음1')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Enter', metaKey: true })
    await waitFor(() => expect(props.onDone).toHaveBeenCalledWith({ activityIds: [500, 501], saved: 2, skipped: 1, propagated: 4 }))
    expect(props.onClose).toHaveBeenCalled()
  })

  it('save error stays open with message', async () => {
    polish.mockImplementation(async (ids: number[]) => result(ids))
    saveBatch.mockRejectedValue(new Error('저장 실패'))
    const props = render(rowsOf(1))
    await start()
    await screen.findByText('다듬음1')
    await userEvent.click(screen.getByRole('button', { name: '넣기 (1행)' }))
    expect(await screen.findByText('저장 실패')).toBeInTheDocument()
    expect(props.onDone).not.toHaveBeenCalled()
    expect(props.onClose).not.toHaveBeenCalled()
  })
})
