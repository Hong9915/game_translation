import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../../api/client'
import MemoPanel from './MemoPanel'

// 실제 편집기 동작은 브라우저에서 본다. 여기서는 불러오기·자동 저장·충돌 처리 흐름만 시험한다.
const fake = vi.hoisted(() => ({
  options: null as null | { onUpdate: (a: { editor: unknown }) => void },
  doc: { type: 'doc', content: [] as unknown[] },
  attrs: {} as { fontSize?: string },
  commands: { setFontSize: vi.fn(() => ({ run: () => true })), unsetFontSize: vi.fn(() => ({ run: () => true })) },
  editor: {
    isFocused: false,
    isActive: () => false,
    getAttributes: () => fake.attrs,
    state: { selection: { empty: true } },
    chain: () => ({ focus: () => fake.commands }),
    getJSON: () => fake.doc,
    setEditable: vi.fn(),
    commands: { setContent: vi.fn() },
  },
}))
vi.mock('@tiptap/react', () => ({
  useEditor: (o: typeof fake.options) => { fake.options = o; return fake.editor },
  EditorContent: () => <div data-testid="editor" />,
}))

const type = (text: string) => act(() => {
  fake.doc = { type: 'doc', content: [{ type: 'text', text }] }
  fake.options!.onUpdate({ editor: fake.editor })
})
const memo = (version: number, content: string | null = null) => ({ content, version, updatedAt: version ? '2026-11-09T00:00:00Z' : null, updatedByName: null })

function show() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><MemoPanel gameId={7} saveDelay={0} /></QueryClientProvider>)
}

describe('MemoPanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    fake.editor.setEditable.mockClear()
    fake.editor.commands.setContent.mockClear()
    fake.editor.isFocused = false
    fake.attrs = {}
    fake.editor.state.selection.empty = true
    fake.commands.setFontSize.mockClear()
    fake.commands.unsetFontSize.mockClear()
    localStorage.clear()
  })

  it('드래그 없이 고른 크기는 내 기본값이 되고 다시 열어도 남는다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    const view = show()
    await waitFor(() => expect(screen.getByRole('combobox', { name: '글자 크기' })).toBeEnabled())
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '글자 크기' }), '24')
    expect(screen.getByRole('combobox', { name: '글자 크기' })).toHaveValue('24')
    expect(fake.commands.setFontSize).not.toHaveBeenCalled()
    view.unmount()
    show()
    await waitFor(() => expect(screen.getByRole('combobox', { name: '글자 크기' })).toHaveValue('24'))
  })

  it('저장된 기본값이 이상하면 32로 연다', async () => {
    localStorage.setItem('memo-base-font-size', 'abc')
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    show()
    await waitFor(() => expect(screen.getByRole('combobox', { name: '글자 크기' })).toHaveValue('32'))
  })

  it('글자 크기는 기본 32이고 드래그한 글자에 고르면 그 부분만 바꾸고 기본값은 그대로다', async () => {
    fake.editor.state.selection.empty = false
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    show()
    await waitFor(() => expect(screen.getByRole('combobox', { name: '글자 크기' })).toBeEnabled())
    expect(screen.getByRole('combobox', { name: '글자 크기' })).toHaveValue('32')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '글자 크기' }), '28')
    expect(fake.commands.setFontSize).toHaveBeenCalledWith('28px')
    expect(localStorage.getItem('memo-base-font-size')).toBeNull()
  })

  it('드래그한 글자를 기본 크기로 돌리면 크기 표시를 지운다', async () => {
    fake.attrs = { fontSize: '28px' }
    fake.editor.state.selection.empty = false
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    show()
    await waitFor(() => expect(screen.getByRole('combobox', { name: '글자 크기' })).toBeEnabled())
    expect(screen.getByRole('combobox', { name: '글자 크기' })).toHaveValue('28')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '글자 크기' }), '32')
    expect(fake.commands.unsetFontSize).toHaveBeenCalledTimes(1)
    expect(fake.commands.setFontSize).not.toHaveBeenCalled()
  })

  it('목록에 없는 크기의 글도 그 크기로 보여 준다', async () => {
    fake.attrs = { fontSize: '11px' }
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    show()
    await waitFor(() => expect(screen.getByRole('combobox', { name: '글자 크기' })).toHaveValue('11'))
  })

  it('불러온 뒤에야 쓸 수 있게 하고 저장된 내용을 넣는다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(3, '{"type":"doc"}'))
    show()
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalledWith(true, false))
    expect(fake.editor.commands.setContent).toHaveBeenCalledWith({ type: 'doc' }, { emitUpdate: false })
  })

  it('불러오기에 실패하면 쓸 수 없고 안내한다 — 빈 문서로 덮어쓰지 않게', async () => {
    vi.spyOn(api.games, 'memo').mockRejectedValue(new ApiError(500, 'x'))
    show()
    expect(await screen.findByRole('alert')).toHaveTextContent('메모를 불러오지 못했습니다')
    expect(fake.editor.setEditable).not.toHaveBeenCalled()
  })

  it('입력하면 마지막 버전을 실어 저장하고, 다음 저장은 새 버전으로 한다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(3, '{"type":"doc"}'))
    const save = vi.spyOn(api.games, 'saveMemo').mockResolvedValueOnce(memo(4)).mockResolvedValueOnce(memo(5))
    show()
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('가')
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    expect(save.mock.calls[0]).toEqual([7, { content: JSON.stringify(fake.doc), baseVersion: 3 }])
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('저장됨'))
    type('나')
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2))
    expect(save.mock.calls[1][1].baseVersion).toBe(4)
  })

  it('닫으면 아직 저장 안 한 글을 저장한다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    const save = vi.spyOn(api.games, 'saveMemo').mockResolvedValue(memo(1))
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = render(<QueryClientProvider client={qc}><MemoPanel gameId={7} saveDelay={60_000} /></QueryClientProvider>)
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('닫기 전')
    expect(save).not.toHaveBeenCalled()
    view.unmount()
    expect(save).toHaveBeenCalledTimes(1)
  })

  it('창을 닫으면 묻지 않고 keepalive로 저장한다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(3, '{"type":"doc"}'))
    const save = vi.spyOn(api.games, 'saveMemo').mockResolvedValue(memo(4))
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><MemoPanel gameId={7} saveDelay={60_000} /></QueryClientProvider>)
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('닫기 직전')
    const before = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(before)
    expect(before.defaultPrevented).toBe(false)
    window.dispatchEvent(new Event('pagehide'))
    expect(save).toHaveBeenCalledTimes(1)
    expect(save.mock.calls[0]).toEqual([7, { content: JSON.stringify(fake.doc), baseVersion: 3 }, { keepalive: true }])
  })

  it('한 번에 보낼 수 없을 만큼 길면 저장을 시도하지 않고 닫기 전에 묻는다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(3, '{"type":"doc"}'))
    const save = vi.spyOn(api.games, 'saveMemo').mockResolvedValue(memo(4))
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={qc}><MemoPanel gameId={7} saveDelay={60_000} /></QueryClientProvider>)
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('가'.repeat(25_000))
    const before = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(before)
    expect(before.defaultPrevented).toBe(true)
    window.dispatchEvent(new Event('pagehide'))
    expect(save).not.toHaveBeenCalled()
  })

  it('충돌하면 멈추고, 덮어쓰기를 고르면 최신 버전으로 다시 저장한다', async () => {
    const get = vi.spyOn(api.games, 'memo').mockResolvedValue(memo(3, '{"type":"doc"}'))
    const save = vi.spyOn(api.games, 'saveMemo').mockRejectedValueOnce(new ApiError(409, '충돌')).mockResolvedValueOnce(memo(10))
    show()
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('내 글')
    expect(await screen.findByRole('alert')).toHaveTextContent('다른 분이 먼저')
    get.mockResolvedValue(memo(9, '{"type":"doc","x":1}'))
    await userEvent.click(screen.getByRole('button', { name: '내 글로 덮어쓰기' }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2))
    expect(save.mock.calls[1][1].baseVersion).toBe(9)
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })

  it('충돌에서 최신 내용을 고르면 내 글을 버리고 저장하지 않는다', async () => {
    const get = vi.spyOn(api.games, 'memo').mockResolvedValue(memo(3, '{"type":"doc"}'))
    const save = vi.spyOn(api.games, 'saveMemo').mockRejectedValueOnce(new ApiError(409, '충돌'))
    show()
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('내 글')
    await screen.findByRole('alert')
    get.mockResolvedValue(memo(9, '{"type":"doc","x":1}'))
    fake.editor.commands.setContent.mockClear()
    await userEvent.click(screen.getByRole('button', { name: /최신 내용 불러오기/ }))
    await waitFor(() => expect(fake.editor.commands.setContent).toHaveBeenCalledWith({ type: 'doc', x: 1 }, { emitUpdate: false }))
    expect(save).toHaveBeenCalledTimes(1)
  })

  it('저장 실패는 안내하고 다시 저장할 수 있다', async () => {
    vi.spyOn(api.games, 'memo').mockResolvedValue(memo(0))
    const save = vi.spyOn(api.games, 'saveMemo').mockRejectedValueOnce(new ApiError(400, '메모가 너무 깁니다.')).mockResolvedValueOnce(memo(1))
    show()
    await waitFor(() => expect(fake.editor.setEditable).toHaveBeenCalled())
    type('글')
    expect(await screen.findByRole('alert')).toHaveTextContent('메모가 너무 깁니다.')
    await userEvent.click(screen.getByRole('button', { name: '다시 저장' }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  })
})
