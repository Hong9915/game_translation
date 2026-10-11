import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'
import { VariantsTab } from './VariantsTab'

const ex = (src: string, ko: string) => ({ src, ko })
export const variants = [
  {
    find: '每日', kind: 'ALT', rows: 131, missingRows: 0, missing: [], standard: null,
    main: { ko: '일일', rows: 74, spacing: false, examples: [ex('每日任务', '일일 임무')] },
    alts: [
      { ko: '매일', rows: 49, spacing: false, examples: [ex('每日签到', '매일 출석')] },
      { ko: '하루에', rows: 5, spacing: false, examples: [ex('每日可领取一次', '하루에 한 번 받을 수 있음')] },
    ],
  },
  {
    find: '生命', kind: 'ALT', rows: 435, missingRows: 0, missing: [], standard: null,
    main: { ko: '생명', rows: 232, spacing: false, examples: [ex('生命果实', '생명 열매')] },
    alts: [{ ko: '체력', rows: 123, spacing: false, examples: [ex('生命值', '체력')] }],
  },
  {
    find: '野猪王', kind: 'SPACING', rows: 32, missingRows: 0, missing: [], standard: '멧돼지 왕',
    main: { ko: '멧돼지왕', rows: 21, spacing: false, examples: [ex('击败野猪王', '멧돼지왕 처치')] },
    alts: [{ ko: '멧돼지 왕', rows: 11, spacing: true, examples: [ex('野猪王的愤怒', '멧돼지 왕의 분노')] }],
  },
  {
    find: '凯瑞甘', kind: 'MISSING', rows: 5, missingRows: 1, missing: [ex('虫群女王·凯瑞甘', '군체의 지배자: 렉시온')], standard: null,
    main: { ko: '케리건', rows: 4, spacing: false, examples: [ex('凯瑞甘', '케리건')] },
    alts: [],
  },
]
const saved = { entry: { id: 1, gameId: 7, kind: 'SRC', matchMode: 'PARTIAL', find: 'x', ko: null, note: null, oldKo: null, shared: false, updatedAt: '' }, applied: 0 }
const literal = { 每日: '매일', 生命: '생명', 野猪王: '멧돼지 왕' }
const routes = () => [
  { method: 'POST', path: '/glossary/literal', body: literal },
  { path: '/games/7/glossary/variants', body: variants },
  { method: 'POST', path: '/games/7/glossary/categories', body: {} },
  { method: 'POST', path: '/games/7/glossary/unify-spacing', body: { applied: 11 } },
  { method: 'POST', path: '/games/7/glossary/unify', body: { ...saved, applied: 5 } },
  { path: '/games/7/glossary/apply/count', body: { count: 2 } },
  { method: 'POST', path: '/games/7/glossary', body: saved },
]
const render = () => renderWithProviders(<VariantsTab gameId={7} />)
const card = (find: string) => screen.getByRole('article', { name: find })

describe('VariantsTab', () => {
  it('다른 표기 카드를 먼저 보여 주고, 종류 칩으로 바꿔 본다', async () => {
    mockFetch(routes())
    render()
    expect(await screen.findByRole('article', { name: '每日' })).toBeInTheDocument()
    expect(within(card('每日')).getByText('일일 · 74행')).toBeInTheDocument()
    expect(within(card('每日')).getByText('하루에 · 5행')).toBeInTheDocument()
    expect(within(card('每日')).getByText('하루에', { selector: 'mark' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: '野猪王' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다른 표기 2' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: '띄어쓰기 1' }))
    expect(within(card('野猪王')).getByText('멧돼지 왕 · 11행')).toBeInTheDocument()
    expect(within(card('野猪王')).queryByRole('button', { name: '뜻이 다름' })).not.toBeInTheDocument()
  })

  it('검색어로 원문·표기를 거른다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '每日' })
    await userEvent.type(screen.getByRole('searchbox', { name: '원문·표기 검색' }), '체력')
    expect(screen.queryByRole('article', { name: '每日' })).not.toBeInTheDocument()
    expect(screen.getByRole('article', { name: '生命' })).toBeInTheDocument()
  })

  it('뜻이 다름은 메모 항목으로 저장하고 카드를 접는다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '生命' })
    await userEvent.click(within(card('生命')).getByRole('button', { name: '뜻이 다름' }))
    const memo = within(card('生命')).getByRole('textbox', { name: '메모' })
    expect(memo).toHaveValue('문맥에 따라 생명/체력')
    await userEvent.click(within(card('生命')).getByRole('button', { name: '메모 저장' }))
    expect(calls.find((c) => c.method === 'POST' && c.url.endsWith('/games/7/glossary'))?.body).toEqual({ kind: 'SRC', matchMode: 'PARTIAL', find: '生命', ko: null, note: '문맥에 따라 생명/체력' })
    expect(await within(card('生命')).findByText('처리함 · 뜻이 다름, 메모로 남김')).toBeInTheDocument()
  })

  it('주 표기 빠짐은 단어장에 번역 항목으로 등록한다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '每日' })
    await userEvent.click(screen.getByRole('button', { name: '주 표기 빠짐 1' }))
    expect(within(card('凯瑞甘')).getByText('렉시온', { exact: false })).toBeInTheDocument()
    await userEvent.click(within(card('凯瑞甘')).getByRole('button', { name: '단어장에 등록' }))
    expect(calls.find((c) => c.method === 'POST' && c.url.endsWith('/games/7/glossary'))?.body).toEqual({ kind: 'SRC', matchMode: 'PARTIAL', find: '凯瑞甘', ko: '케리건' })
    expect(await within(card('凯瑞甘')).findByText('처리함 · 단어장에 등록, 凯瑞甘 → 케리건')).toBeInTheDocument()
  })

  it('저장이 실패하면 카드 안에 서버 메시지를 보여 준다', async () => {
    mockFetch([{ path: '/games/7/glossary/variants', body: variants }, { method: 'POST', path: '/games/7/glossary', status: 400, body: { message: '이미 같은 항목이 있습니다.' } }])
    render()
    await screen.findByRole('article', { name: '每日' })
    await userEvent.click(screen.getByRole('button', { name: '주 표기 빠짐 1' }))
    await userEvent.click(within(card('凯瑞甘')).getByRole('button', { name: '단어장에 등록' }))
    expect(await within(card('凯瑞甘')).findByRole('alert')).toHaveTextContent('이미 같은 항목이 있습니다.')
  })

  it('보고 있는 종류의 원문을 한 번에 물어 DeepL 직역을 원문 옆에 띄운다', async () => {
    const { calls } = mockFetch(routes())
    render()
    expect(await within(await screen.findByRole('article', { name: '每日' })).findByText('직역: 매일')).toBeInTheDocument()
    expect(within(card('生命')).getByText('직역: 생명')).toBeInTheDocument()
    expect(calls.filter((c) => c.url.includes('/glossary/literal')).map((c) => c.body)).toEqual([{ texts: ['每日', '生命'] }])
    await userEvent.click(screen.getByRole('button', { name: '띄어쓰기 1' }))
    expect(await within(card('野猪王')).findByText('직역: 멧돼지 왕')).toBeInTheDocument()
    expect(calls.filter((c) => c.url.includes('/glossary/literal')).map((c) => c.body)).toEqual([{ texts: ['每日', '生命'] }, { texts: ['野猪王'] }])
  })

  it('직역을 못 받아도 카드는 그대로 보인다', async () => {
    mockFetch([{ method: 'POST', path: '/glossary/literal', status: 500, body: { message: 'DeepL 오류' } }, ...routes().slice(1)])
    render()
    expect(await screen.findByRole('article', { name: '每日' })).toBeInTheDocument()
    expect(screen.queryByText(/^직역: /)).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('띄어쓰기 카드에 Kiwi 표준 꼴을 보여 준다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '每日' })
    expect(within(card('每日')).queryByText(/^표준:/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '띄어쓰기 1' }))
    expect(within(card('野猪王')).getByText('표준: 멧돼지 왕')).toBeInTheDocument()
  })

  it('띄어쓰기는 고른 쪽으로 한꺼번에 고치고, 카드를 접고 고친 행 수를 알린다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '每日' })
    await userEvent.click(screen.getByRole('button', { name: '띄어쓰기 1' }))
    expect(screen.getByRole('radio', { name: '많이 쓴 꼴' })).toBeChecked()
    await userEvent.click(screen.getByRole('radio', { name: '표준 띄어쓰기' }))
    await userEvent.click(screen.getByRole('button', { name: '한꺼번에 통일…' }))
    const dialog = screen.getByRole('dialog', { name: '띄어쓰기 한꺼번에 통일' })
    expect(within(dialog).getByRole('checkbox', { name: '野猪王 멧돼지왕 → 멧돼지 왕' })).toBeChecked()
    await userEvent.click(within(dialog).getByRole('button', { name: '고치기' }))
    expect(calls.find((c) => c.url.includes('/unify-spacing'))?.body).toEqual({ items: [{ find: '野猪王', ko: '멧돼지 왕', replace: ['멧돼지왕'] }] })
    expect(await within(card('野猪王')).findByText('처리함 · 띄어쓰기 통일, 멧돼지 왕')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('띄어쓰기 1개 구절에서 11행을 고쳤습니다.')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('한꺼번에 통일 창에서 다 빼면 고칠 수 없다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '每日' })
    await userEvent.click(screen.getByRole('button', { name: '띄어쓰기 1' }))
    await userEvent.click(screen.getByRole('button', { name: '한꺼번에 통일…' }))
    const dialog = screen.getByRole('dialog', { name: '띄어쓰기 한꺼번에 통일' })
    await userEvent.click(within(dialog).getByRole('checkbox', { name: '野猪王 멧돼지 왕 → 멧돼지왕' }))
    expect(within(dialog).getByRole('button', { name: '고치기' })).toBeDisabled()
  })

  it('통일…은 통일 창을 열고, 끝나면 카드를 접는다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('article', { name: '每日' })
    await userEvent.click(within(card('每日')).getByRole('button', { name: '통일…' }))
    await userEvent.click(screen.getByRole('button', { name: '통일' }))
    expect(await within(card('每日')).findByText('처리함 · 일일로 통일 · 5행')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
