import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { TermRequestDetail, TermRequestItem } from '../api/types'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { TermRequestPage } from './TermRequestPage'

const game = { id: 7, name: '숲의 소환사', projects: 2, memory: 2318, lastUploadedAt: '2026-09-15T09:00:00Z', createdAt: '2026-09-01T00:00:00Z' }
const item = (over: Partial<TermRequestItem>): TermRequestItem => ({
  id: 0, seq: 0, category: 'OTHER', src: '', renderings: [], source: 'GPT', gptKo: null, gptReason: null,
  status: 'READY', finalKo: null, existingKo: null, existingNote: null, gptChecked: false, gptFindReason: null, ...over,
})
const trap = item({
  id: 1, seq: 1, category: 'CHARACTER', src: '陷阱', source: 'CORPUS',
  renderings: [
    { ko: '함정', rows: 4, exampleSrc: '踩到陷阱了', exampleKo: '함정을 밟았다' },
    { ko: '트랩', rows: 2, exampleSrc: '陷阱已解除', exampleKo: '트랩 해제됨' },
  ],
})
const daily = item({ id: 2, seq: 2, category: 'OTHER', src: '每日', gptKo: '매일', gptReason: '일일 반복 보상' })
const wolf = item({ id: 3, seq: 3, category: 'MONSTER', src: '暗影狼王', status: 'PENDING' })
const gold = item({ id: 4, seq: 4, category: 'CURRENCY', src: '金', gptKo: '골드', gptReason: '화폐' })
const boar = item({
  id: 5, seq: 5, category: 'MONSTER', src: '野猪王', source: 'CORPUS',
  renderings: [
    { ko: '멧돼지왕', rows: 4, exampleSrc: '击败野猪王', exampleKo: '멧돼지왕 처치' },
    { ko: '멧돼지 왕', rows: 1, exampleSrc: '野猪王出现', exampleKo: '멧돼지 왕 등장' },
  ],
})
const detail = (items: TermRequestItem[]): TermRequestDetail => ({ id: 3, gameId: 7, fileName: '용어요청.xlsx', createdAt: '2026-10-30T09:00:00Z', items })
const initial = detail([trap, daily, wolf, gold, boar])
const generated = detail([trap, daily, { ...wolf, status: 'READY', gptKo: '그림자 늑대왕', gptReason: '몬스터 보스' }, gold, boar])
const approvedTrap = detail([{ ...trap, status: 'APPROVED', finalKo: '함정' }, ...generated.items.slice(1)])

const routes = () => [
  // mockFetch는 url.includes로 첫 일치 route를 고른다 — 더 구체적인 경로를 앞에 둔다
  { method: 'POST', path: '/glossary/requests/3/generate', body: generated },
  { method: 'POST', path: '/glossary/requests/3/approve', body: { approved: 1, applied: 2, request: approvedTrap } },
  { path: '/glossary/requests/3', body: initial },
  { path: '/games/7', body: game },
]
const render = () => renderWithProviders(<TermRequestPage />, {
  route: '/games/7/glossary/requests/3', path: '/games/:gameId/glossary/requests/:requestId',
})
const rowOf = (src: string) => screen.getByRole('row', { name: new RegExp(src) })
const approveBodies = (calls: { method: string; url: string; body: unknown }[]) =>
  calls.filter((c) => c.method === 'POST' && c.url.includes('/approve')).map((c) => c.body)

describe('TermRequestPage', () => {
  it('번역본 속 표기와 GPT 초안을 한 줄씩 보여 준다', async () => {
    mockFetch(routes())
    render()
    expect(await screen.findByRole('heading', { name: '단어장 요청 — 숲의 소환사' })).toBeInTheDocument()
    // 09:00Z는 어느 시간대에서도 10월 30일이다 — 로컬 날짜 형식(ko-KR)으로 보인다
    expect(screen.getByText(/용어요청\.xlsx · 2026\. 10\. 30\./)).toBeInTheDocument()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    expect(row.getByRole('button', { name: '함정 4' })).toBeInTheDocument()
    expect(row.getByRole('button', { name: '트랩 2' })).toBeInTheDocument()
    expect(row.getByLabelText('陷阱 최종 표기')).toHaveValue('함정')
    const wolfRow = within(await screen.findByRole('row', { name: /暗影狼王/ }))
    expect(await wolfRow.findByLabelText('暗影狼王 최종 표기')).toHaveValue('그림자 늑대왕')
    expect(wolfRow.getByText('GPT')).toBeInTheDocument()
    expect(wolfRow.getByText('번역본에 없음')).toBeInTheDocument()
    // 원문 1글자는 대조하지 않는다
    expect(within(rowOf('金')).getByText('한 글자 — 칸 전체가 같을 때만 적용')).toBeInTheDocument()
  })

  it('PENDING이 있으면 GPT 초안을 한 번 요청한다', async () => {
    const { calls } = mockFetch(routes())
    render()
    expect(await within(await screen.findByRole('row', { name: /暗影狼王/ })).findByLabelText('暗影狼王 최종 표기')).toHaveValue('그림자 늑대왕')
    expect(calls.filter((c) => c.url.includes('/generate')).length).toBe(1)
  })

  it('다른 표기는 기본으로 바꾸기에 체크돼 있고 승인하면 replace로 보낸다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    expect(row.getByRole('checkbox', { name: '트랩 2행' })).toBeChecked()
    await userEvent.click(row.getByRole('button', { name: '승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [{ itemId: 1, ko: '함정', replace: ['트랩'] }] }]))
    expect(await screen.findByText('1개 승인 · 기존 번역 2행 고침')).toBeInTheDocument()
  })

  it('다른 표기 체크를 풀면 replace에서 빠진다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    await userEvent.click(row.getByRole('checkbox', { name: '트랩 2행' }))
    await userEvent.click(row.getByRole('button', { name: '승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [{ itemId: 1, ko: '함정', replace: [] }] }]))
  })

  it('칩을 누르면 최종 표기가 바뀌고, 바꿀 표기 목록도 따라 바뀐다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    await userEvent.click(row.getByRole('button', { name: '트랩 2' }))
    expect(row.getByLabelText('陷阱 최종 표기')).toHaveValue('트랩')
    expect(row.getByRole('checkbox', { name: '함정 4행' })).toBeChecked()
    expect(row.queryByRole('checkbox', { name: '트랩 2행' })).not.toBeInTheDocument()
    // 직접 고쳐 써도 두 표기 모두 다른 표기가 된다
    await userEvent.clear(row.getByLabelText('陷阱 최종 표기'))
    await userEvent.type(row.getByLabelText('陷阱 최종 표기'), '덫')
    expect(row.getByRole('checkbox', { name: '트랩 2행' })).toBeChecked()
    await userEvent.click(row.getByRole('button', { name: '승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [{ itemId: 1, ko: '덫', replace: ['함정', '트랩'] }] }]))
  })

  it('선택 승인 버튼이 바뀔 행 수를 미리 보여 준다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('row', { name: /陷阱/ })
    await userEvent.click(within(rowOf('陷阱')).getByRole('checkbox', { name: '陷阱 선택' }))
    await userEvent.click(within(rowOf('野猪王')).getByRole('checkbox', { name: '野猪王 선택' }))
    expect(screen.getByText('기존 번역 약 3행이 바뀝니다')).toBeInTheDocument()
    await userEvent.click(within(rowOf('野猪王')).getByRole('checkbox', { name: '멧돼지 왕 1행' }))
    expect(screen.getByText('기존 번역 약 2행이 바뀝니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '선택한 2개 승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [
      { itemId: 1, ko: '함정', replace: ['트랩'] },
      { itemId: 5, ko: '멧돼지왕', replace: [] },
    ] }]))
  })

  it('표기 갈림 탭은 표기가 둘 이상인 미승인 줄만', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('row', { name: /陷阱/ })
    await userEvent.click(screen.getByRole('tab', { name: '표기 갈림 2' }))
    const srcs = screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[2].textContent)
    expect(srcs).toEqual(['陷阱', '野猪王'])
  })

  it('승인된 줄은 읽기 전용이라 승인 버튼·선택이 없고 일괄 승인에도 들지 않는다', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/glossary/requests/3/approve', body: { approved: 1, applied: 1, request: approvedTrap } },
      { path: '/glossary/requests/3', body: approvedTrap },
      { path: '/games/7', body: game },
    ])
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    expect(row.getByText('승인됨')).toBeInTheDocument()
    expect(row.queryByRole('button', { name: '승인' })).not.toBeInTheDocument()
    expect(row.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(row.queryByRole('button', { name: '트랩 2' })).not.toBeInTheDocument()
    expect(row.queryByLabelText('陷阱 최종 표기')).not.toBeInTheDocument()
    expect(row.getByText('함정', { selector: 'span.font-semibold' })).toBeInTheDocument()

    await userEvent.click(within(rowOf('野猪王')).getByRole('checkbox', { name: '野猪王 선택' }))
    expect(screen.getByText('기존 번역 약 1행이 바뀝니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '선택한 1개 승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [{ itemId: 5, ko: '멧돼지왕', replace: ['멧돼지 왕'] }] }]))
  })

  it('최종 표기 끝에 공백이 있어도 자기 자신을 다른 표기로 세지 않는다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    await userEvent.type(row.getByLabelText('陷阱 최종 표기'), ' ')
    expect(row.queryByRole('checkbox', { name: '함정 4행' })).not.toBeInTheDocument()
    await userEvent.click(row.getByRole('checkbox', { name: '陷阱 선택' }))
    expect(screen.getByText('기존 번역 약 2행이 바뀝니다')).toBeInTheDocument()
    await userEvent.click(row.getByRole('button', { name: '승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [{ itemId: 1, ko: '함정', replace: ['트랩'] }] }]))
  })

  it('자동 GPT 초안 요청이 실패하면 만드는 중 대신 다시 만들기를 보인다', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/glossary/requests/3/generate', status: 500, body: { message: '서버 오류' } },
      ...routes().slice(1),
    ])
    render()
    const row = within(await screen.findByRole('row', { name: /暗影狼王/ }))
    expect(await row.findByText('GPT 실패')).toBeInTheDocument()
    expect(row.queryByText('GPT 만드는 중…')).not.toBeInTheDocument()
    await userEvent.click(row.getByRole('button', { name: '다시 만들기' }))
    await waitFor(() => expect(calls.filter((c) => c.url.includes('/generate')).length).toBe(2))
  })

  it('다시 만들기가 성공하면 오류 줄이 사라진다', async () => {
    // 첫 요청은 500, 오류가 보인 뒤 같은 경로를 성공으로 바꾼다(mockFetch는 route 객체를 부를 때마다 읽는다)
    const gen = { method: 'POST', path: '/glossary/requests/3/generate', status: 500, body: { message: '서버 오류' } as unknown }
    mockFetch([gen, ...routes().slice(1)])
    render()
    expect(await screen.findByText(/GPT 초안을 만들지 못했습니다/)).toBeInTheDocument()
    gen.status = 200
    gen.body = generated
    await userEvent.click(within(rowOf('暗影狼王')).getByRole('button', { name: '다시 만들기' }))
    expect(await within(rowOf('暗影狼王')).findByDisplayValue('그림자 늑대왕')).toBeInTheDocument()
    expect(screen.queryByText(/GPT 초안을 만들지 못했습니다/)).not.toBeInTheDocument()
  })

  it('GPT 초안보다 단어장에 이미 있는 표기를 최종 표기로 먼저 둔다', async () => {
    const coin = item({ id: 6, seq: 6, category: 'CURRENCY', src: '金币', gptKo: '코인', gptReason: '화폐', existingKo: '금화' })
    const { calls } = mockFetch([
      { method: 'POST', path: '/glossary/requests/3/approve', body: { approved: 1, applied: 0, request: detail([{ ...coin, status: 'APPROVED', finalKo: '금화' }]) } },
      { path: '/glossary/requests/3', body: detail([coin]) },
      { path: '/games/7', body: game },
    ])
    render()
    const row = within(await screen.findByRole('row', { name: /金币/ }))
    expect(row.getByLabelText('金币 최종 표기')).toHaveValue('금화')
    expect(row.queryByText('GPT')).not.toBeInTheDocument()
    await userEvent.click(row.getByRole('button', { name: '승인' }))
    await waitFor(() => expect(approveBodies(calls)).toEqual([{ items: [{ itemId: 6, ko: '금화', replace: [] }] }]))
  })

  it('승인이 실패하면 요청을 다시 읽어 그새 승인된 줄을 승인됨으로 보인다', async () => {
    // 서버는 陷阱까지 승인하고 실패했다 — 다시 읽으면 승인됨으로 온다
    const req = { path: '/glossary/requests/3', body: generated as TermRequestDetail }
    mockFetch([
      { method: 'POST', path: '/glossary/requests/3/approve', status: 500, body: { message: '서버 오류' } },
      req,
      { path: '/games/7', body: game },
    ])
    render()
    const button = within(await screen.findByRole('row', { name: /陷阱/ })).getByRole('button', { name: '승인' })
    expect(screen.getByRole('tab', { name: '승인됨 0' })).toBeInTheDocument()
    req.body = approvedTrap
    await userEvent.click(button)
    expect(await screen.findByText(/승인하지 못했습니다/)).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: '승인됨 1' })).toBeInTheDocument()
  })

  it('탭이나 분류를 바꾸면 선택이 풀린다', async () => {
    mockFetch(routes())
    render()
    await userEvent.click(within(await screen.findByRole('row', { name: /陷阱/ })).getByRole('checkbox', { name: '陷阱 선택' }))
    expect(screen.getByRole('button', { name: '선택한 1개 승인' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: /표기 갈림/ }))
    expect(screen.getByRole('button', { name: '선택한 0개 승인' })).toBeDisabled()
    await userEvent.click(screen.getByRole('tab', { name: /전체/ }))
    await userEvent.click(within(rowOf('陷阱')).getByRole('checkbox', { name: '陷阱 선택' }))
    await userEvent.selectOptions(screen.getByLabelText('분류'), '캐릭터')
    expect(screen.getByRole('button', { name: '선택한 0개 승인' })).toBeDisabled()
  })

  it('승인하면 승인됨 탭으로 옮겨 가고 분류로 거른다', async () => {
    mockFetch(routes())
    render()
    await userEvent.click(within(await screen.findByRole('row', { name: /陷阱/ })).getByRole('button', { name: '승인' }))
    expect(await screen.findByRole('tab', { name: '승인됨 1' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '표기 갈림 1' })).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('분류'), '몬스터')
    expect(screen.queryByRole('row', { name: /陷阱/ })).not.toBeInTheDocument()
    expect(rowOf('野猪王')).toBeInTheDocument()
  })
})

describe('GPT로 표기 확인', () => {
  const checked = detail([
    { ...trap, renderings: [trap.renderings[0]], gptChecked: true, gptFindReason: '트랩은 다른 문장의 낱말' },
    ...generated.items.slice(1).map((i) => ({ ...i, gptChecked: [...i.src].length > 1 })),   // 1글자 金은 확인 대상이 아니다
  ])
  const findRoute = { method: 'POST', path: '/glossary/requests/3/find', body: checked as unknown, status: 200 }

  it('GPT로 표기 확인을 누르면 표기가 GPT 결과로 바뀐다', async () => {
    const { calls } = mockFetch([findRoute, ...routes()])
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    expect(row.getByRole('button', { name: '트랩 2' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'GPT로 표기 확인' }))
    await waitFor(() => expect(row.queryByRole('button', { name: '트랩 2' })).not.toBeInTheDocument())
    expect(row.getByRole('button', { name: '함정 4' })).toBeInTheDocument()
    expect(row.getByText('GPT 확인')).toBeInTheDocument()
    expect(row.getByText('트랩은 다른 문장의 낱말')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST' && c.url.includes('/find')).length).toBe(1)
    expect(await screen.findByRole('status')).toHaveTextContent('4개 확인')
  })

  it('확인 중에는 버튼이 막힌다', async () => {
    const { fn } = mockFetch(routes())
    const real = fn.getMockImplementation()!
    fn.mockImplementation((input, init) => (String(input).includes('/find') ? new Promise<Response>(() => {}) : real(input, init)))
    render()
    await screen.findByRole('row', { name: /陷阱/ })
    await userEvent.click(screen.getByRole('button', { name: 'GPT로 표기 확인' }))
    const busy = await screen.findByRole('button', { name: '확인 중…' })
    expect(busy).toBeDisabled()
  })

  it('확인할 항목이 없으면 버튼이 꺼져 있다', async () => {
    mockFetch([
      { path: '/glossary/requests/3', body: detail([{ ...trap, gptChecked: true }, gold]) },
      { path: '/games/7', body: game },
    ])
    render()
    await screen.findByRole('row', { name: /陷阱/ })
    expect(screen.getByRole('button', { name: 'GPT로 표기 확인' })).toBeDisabled()
  })

  it('확인이 실패하면 오류를 보인다', async () => {
    mockFetch([{ ...findRoute, status: 500, body: { message: '서버 오류' } }, ...routes()])
    render()
    await screen.findByRole('row', { name: /陷阱/ })
    await userEvent.click(screen.getByRole('button', { name: 'GPT로 표기 확인' }))
    expect(await screen.findByText(/GPT로 표기를 확인하지 못했습니다/)).toBeInTheDocument()
  })
})

describe('GPT로 표기 확인 — 검토 반영', () => {
  it('확인으로 표기가 바뀌면 전에 고른 최종 표기를 버리고 새 주 표기를 쓴다', async () => {
    const checked = detail([{ ...trap, renderings: [trap.renderings[0]], gptChecked: true }, ...generated.items.slice(1)])
    mockFetch([{ method: 'POST', path: '/glossary/requests/3/find', body: checked }, ...routes()])
    render()
    const row = within(await screen.findByRole('row', { name: /陷阱/ }))
    await userEvent.click(row.getByRole('button', { name: '트랩 2' }))
    expect(row.getByLabelText('陷阱 최종 표기')).toHaveValue('트랩')
    await userEvent.click(screen.getByRole('button', { name: 'GPT로 표기 확인' }))
    await waitFor(() => expect(row.queryByRole('button', { name: '트랩 2' })).not.toBeInTheDocument())
    expect(row.getByLabelText('陷阱 최종 표기')).toHaveValue('함정')
  })

  it('GPT 초안을 만드는 동안에는 표기 확인 버튼이 막힌다', async () => {
    const { fn } = mockFetch(routes())
    const real = fn.getMockImplementation()!
    fn.mockImplementation((input, init) => (String(input).includes('/generate') ? new Promise<Response>(() => {}) : real(input, init)))
    render()
    await screen.findByRole('row', { name: /陷阱/ })
    await waitFor(() => expect(fn.mock.calls.some(([u]) => String(u).includes('/generate'))).toBe(true))
    expect(screen.getByRole('button', { name: 'GPT로 표기 확인' })).toBeDisabled()
  })
})
