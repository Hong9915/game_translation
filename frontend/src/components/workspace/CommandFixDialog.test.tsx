import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, type MockRoute } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'
import { api } from '../../api/client'
import type { CommandPlan } from '../../api/types'
import { CommandFixDialog } from './CommandFixDialog'
import { getCommandRun, resetCommandRuns } from './command/commandRuns'

const REPLACE = { kind: 'REPLACE', filter: null, steps: [{ find: '구미호', replace: '여우', regex: false }] }
const fixPlan = {
  summary: '구미호를 여우로 바꾸고 조사를 맞춥니다.', kind: 'FIX', ops: [REPLACE, { kind: 'JOSA' }],
  trail: [{ tool: 'try_ops', args: '{}', result: '{"changed":2}' }],
  rows: [
    { segmentId: 11, rowNo: 4, src: '九尾狐来了', before: '구미호이 왔다', after: '여우가 왔다', by: ['REPLACE', 'JOSA'], warnings: [], mismatch: null },
    { segmentId: 12, rowNo: 7, src: '{0}九尾狐', before: '{0}구미호', after: '여우', by: ['REPLACE'], warnings: [{ kind: 'TAG', text: '태그·자리표시자 짝이 깨집니다' }], mismatch: null },
  ],
}
const checkPlan = { summary: null, kind: 'CHECK', ops: null, trail: [], rows: [
  { segmentId: 21, rowNo: 9, src: '造成{0}点', before: '{1} 피해', after: null, by: [], warnings: [], mismatch: { src: ['{0}'], ko: ['{1}'] } }] }
const presets = [{ id: 5, name: '대시 공백 지우기', ops: [{ kind: 'REPLACE', filter: null, steps: [{ find: ' - ', replace: '-', regex: false }] }] }]

const routes = (plan: unknown = fixPlan, extra: MockRoute[] = []): MockRoute[] => [
  ...extra,
  { method: 'POST', path: '/review/7/command-fix/plan', body: plan },
  { method: 'POST', path: '/review/7/command-fix', body: { applied: 1 } },
  { path: '/review/7/command-presets', body: presets },
  { method: 'POST', path: '/review/7/command-presets', body: { id: 6, name: '구미호 정리', ops: fixPlan.ops } },
  { method: 'POST', path: '/review/7/marks', body: { updated: 1 } },
]
const render = (onFixed = vi.fn()) => { renderWithProviders(<CommandFixDialog projectId={7} sheet="Skill" onFixed={onFixed} onClose={vi.fn()} />); return { onFixed } }
const ask = async (command = '구미호를 여우로 바꾸고 조사도 맞춰줘') => {
  await userEvent.type(screen.getByRole('textbox', { name: '명령' }), command)
  await userEvent.click(screen.getByRole('button', { name: '미리 보기' }))
}

describe('CommandFixDialog', () => {
  it('미리 보기 전에는 할 수 있는 것·안 되는 것 안내를 보이고, 예시를 누르면 입력칸만 채운다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const guide = screen.getByRole('region', { name: '명령으로 할 수 있는 것' })
    expect(within(guide).getByText('뜻을 보고 다듬기 · 다시 번역')).toBeInTheDocument()
    expect(within(guide).getByText('셀 우클릭 → GPT 번역')).toBeInTheDocument()
    await userEvent.click(within(guide).getByRole('button', { name: '- 앞뒤 띄어쓰기 없애줘' }))
    expect(screen.getByRole('textbox', { name: '명령' })).toHaveValue('- 앞뒤 띄어쓰기 없애줘')
    expect(screen.getByRole('textbox', { name: '명령' })).toHaveFocus()
    expect(calls.some((c) => c.url.endsWith('/command-fix/plan'))).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '미리 보기' }))
    expect(await screen.findByText('구미호를 여우로 바꾸고 조사를 맞춥니다.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '명령으로 할 수 있는 것' })).not.toBeInTheDocument()
  })

  it('거절되면 안내가 다시 보여 대신 갈 곳을 알 수 있다', async () => {
    mockFetch(routes().map((r) => r.path === '/review/7/command-fix/plan' ? { ...r, status: 400, body: { message: '다시 번역해야 해서 할 수 없습니다.' } } : r))
    render()
    await ask('이 문장 자연스럽게 다듬어줘')
    expect(await screen.findByRole('alert')).toHaveTextContent('다시 번역해야 해서 할 수 없습니다.')
    expect(screen.getByRole('region', { name: '명령으로 할 수 있는 것' })).toBeInTheDocument()
  })

  afterEach(() => resetCommandRuns())

  it('창을 닫아도 실행은 끝까지 돌고, 다시 열면 결과와 명령이 그대로 있다', async () => {
    mockFetch(routes())
    let answer!: (p: CommandPlan) => void
    const spy = vi.spyOn(api.workspace, 'commandPlan').mockReturnValueOnce(new Promise<CommandPlan>((res) => { answer = res }))
    const first = renderWithProviders(<CommandFixDialog projectId={7} sheet="Skill" onFixed={vi.fn()} onClose={vi.fn()} />)
    await userEvent.type(screen.getByRole('textbox', { name: '명령' }), '구미호를 여우로')
    await userEvent.click(screen.getByRole('button', { name: '미리 보기' }))
    first.unmount()                                                    // 결과가 오기 전에 닫는다
    answer(fixPlan as CommandPlan)
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    expect(getCommandRun(7)?.seen).toBe(false)
    spy.mockRestore()
    render()
    expect(await screen.findByRole('checkbox', { name: '여우가 왔다' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '명령' })).toHaveValue('구미호를 여우로')
    expect(getCommandRun(7)?.seen).toBe(true)
  })

  it('다른 시트의 실행 결과는 보이지 않고, 본 것으로 치지도 않는다', async () => {
    mockFetch(routes())
    let answer!: (p: CommandPlan) => void
    const spy = vi.spyOn(api.workspace, 'commandPlan').mockReturnValueOnce(new Promise<CommandPlan>((res) => { answer = res }))
    const first = renderWithProviders(<CommandFixDialog projectId={7} sheet="Item" onFixed={vi.fn()} onClose={vi.fn()} />)
    await userEvent.click(screen.getByRole('button', { name: '조사 맞추기' }))
    first.unmount()
    answer(fixPlan as CommandPlan)
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    spy.mockRestore()
    render()                                                           // Skill 시트 창
    expect(screen.queryByRole('checkbox', { name: '여우가 왔다' })).not.toBeInTheDocument()
    expect(getCommandRun(7)?.seen).toBe(false)                         // Item 결과는 아직 안 봤다
  })

  it('명령 결과: 요약·작업 칩·GPT가 한 일·변경 전후를 보이고, 태그 경고 행은 기본으로 빼 둔다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await ask()
    expect(await screen.findByText('구미호를 여우로 바꾸고 조사를 맞춥니다.')).toBeInTheDocument()
    expect(calls.find((c) => c.url.endsWith('/command-fix/plan'))?.body).toEqual({ sheet: 'Skill', command: '구미호를 여우로 바꾸고 조사도 맞춰줘' })
    expect(screen.getByText('① 찾아 바꾸기')).toBeInTheDocument()
    expect(screen.getByText('② 조사 맞추기')).toBeInTheDocument()
    await userEvent.click(screen.getByText('GPT가 한 일 (1)'))
    expect(screen.getByText('시험해 보기')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '여우가 왔다' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '여우' })).not.toBeChecked()
    expect(screen.getByRole('button', { name: '1행 고치기' })).toBeEnabled()
  })

  it('고치기는 시트·명령·작업 목록·고른 행을 보낸다', async () => {
    const { calls } = mockFetch(routes())
    const { onFixed } = render()
    await ask()
    await userEvent.click(await screen.findByRole('button', { name: '1행 고치기' }))
    expect(calls.find((c) => c.url.endsWith('/command-fix') && c.method === 'POST')?.body).toEqual({
      sheet: 'Skill', command: '구미호를 여우로 바꾸고 조사도 맞춰줘', ops: fixPlan.ops,
      items: [{ segmentId: 11, before: '구미호이 왔다', after: '여우가 왔다' }],
    })
    expect(onFixed).toHaveBeenCalledWith(1)
  })

  it('도구 버튼은 GPT 없이 그 작업 하나로 미리 본다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await userEvent.click(screen.getByRole('button', { name: '조사 맞추기' }))
    expect(calls.find((c) => c.url.endsWith('/command-fix/plan'))?.body).toEqual({ sheet: 'Skill', ops: [{ kind: 'JOSA' }] })
    await userEvent.click(screen.getByRole('button', { name: '태그 짝 검사' }))
    expect(calls.filter((c) => c.url.endsWith('/command-fix/plan')).at(-1)?.body).toEqual({ sheet: 'Skill', check: 'TAG_MISMATCH' })
  })

  it('저장 명령 버튼을 누르면 그 작업 목록으로 미리 본다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await userEvent.click(await screen.findByRole('button', { name: '대시 공백 지우기' }))
    expect(calls.find((c) => c.url.endsWith('/command-fix/plan'))?.body).toEqual({ sheet: 'Skill', ops: presets[0].ops })
  })

  it('작업을 저장하면 이름과 작업 목록을 보낸다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await ask()
    await userEvent.click(await screen.findByRole('button', { name: '이 작업 저장' }))
    await userEvent.type(screen.getByRole('textbox', { name: '저장할 이름' }), '구미호 정리')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(calls.find((c) => c.url.endsWith('/command-presets') && c.method === 'POST')?.body).toEqual({ name: '구미호 정리', ops: fixPlan.ops })
  })

  it('작업 칩을 빼면 다시 보기 전까지 고칠 수 없다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await ask()
    await userEvent.click(await screen.findByRole('button', { name: '조사 맞추기 빼기' }))
    expect(screen.getByRole('button', { name: '1행 고치기' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '작업으로 다시 보기' }))
    expect(calls.filter((c) => c.url.endsWith('/command-fix/plan')).at(-1)?.body).toEqual({ sheet: 'Skill', ops: [REPLACE] })
  })

  it('검사 결과는 원문·번역과 토큰을 보이고, 확인 필요로 표시한다', async () => {
    const { calls } = mockFetch(routes(checkPlan))
    render()
    await userEvent.click(screen.getByRole('button', { name: '태그 짝 검사' }))
    const item = await screen.findByRole('listitem', { name: '9행' })
    expect(within(item).getByTitle('번역에 없음')).toHaveTextContent('{0}')
    await userEvent.click(screen.getByRole('button', { name: '1행 확인 필요로 표시' }))
    expect(calls.find((c) => c.url.endsWith('/marks'))?.body).toEqual({ sheet: 'Skill', ids: [21], needsCheck: true })
    expect(await screen.findByRole('status')).toHaveTextContent('1행을 확인 필요로 표시했습니다.')
  })

  it('할 수 없는 명령이면 까닭을 보인다', async () => {
    mockFetch(routes({ message: '뜻을 봐야 해서 못 합니다.' }).map((r) => r.path === '/review/7/command-fix/plan' ? { ...r, status: 400 } : r))
    render()
    await ask('어색한 문장 다듬어줘')
    expect(await screen.findByRole('alert')).toHaveTextContent('뜻을 봐야 해서 못 합니다.')
  })

  it('다시 본 미리 보기가 실패하면 앞 결과를 지워 그걸로 고칠 수 없다', async () => {
    mockFetch(routes())
    render()
    await ask()
    expect(await screen.findByRole('checkbox', { name: '여우가 왔다' })).toBeInTheDocument()
    mockFetch(routes({ message: 'GPT가 작업을 끝내지 못했습니다. 명령을 나눠 보세요.' }).map((r) => r.path === '/review/7/command-fix/plan' ? { ...r, status: 400 } : r))
    await userEvent.click(screen.getByRole('button', { name: '미리 보기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('GPT가 작업을 끝내지 못했습니다.')
    expect(screen.queryByRole('checkbox', { name: '여우가 왔다' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /행 고치기/ })).toBeDisabled()
  })

  it('고치기 오류는 새 미리 보기를 시작하면 사라진다', async () => {
    mockFetch(routes(fixPlan, [{ method: 'POST', path: /\/command-fix$/, status: 400, body: { message: '고칠 행이 없습니다.' } }]))
    render()
    await ask()
    await userEvent.click(await screen.findByRole('button', { name: '1행 고치기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('고칠 행이 없습니다.')
    await userEvent.click(screen.getByRole('button', { name: '조사 맞추기' }))
    expect(await screen.findByText('구미호를 여우로 바꾸고 조사를 맞춥니다.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
