import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { GlossaryRecommendPage } from './GlossaryRecommendPage'

const game = { id: 7, name: '테스트 게임', projects: 2, terms: 120, memory: 2318, lastUploadedAt: '2026-09-15T09:00:00Z', createdAt: '2026-09-01T00:00:00Z' }
const recommendations = [
  {
    find: '伤害', sentences: 883, rows: 1440, termKo: '데미지',
    renderings: [
      { ko: '피해', rows: 1397, exampleSrc: '造成伤害', exampleKo: '피해를 입힌다' },
      { ko: '데미지', rows: 30, exampleSrc: '伤害提升', exampleKo: '데미지 증가' },
    ],
  },
  { find: '图腾', sentences: 150, rows: 167, termKo: '토템', renderings: [{ ko: '토템', rows: 165, exampleSrc: '图腾升级', exampleKo: '토템 강화' }] },
  { find: '敌人', sentences: 123, rows: 166, termKo: null, renderings: [{ ko: '적에게', rows: 70, exampleSrc: '对敌人造成伤害', exampleKo: '적에게 피해' }] },
  { find: '即可', sentences: 118, rows: 130, termKo: null, renderings: [] },
]
const routes = (recs: unknown = recommendations) => [
  // mockFetch는 url.includes로 첫 일치 route를 고른다 — 더 구체적인 경로를 앞에 둔다
  { path: '/games/7/glossary/recommend', body: recs },
  { path: '/games/7/glossary/variants', body: [] },
  { path: '/games/7', body: game },
]
const render = () => renderWithProviders(<GlossaryRecommendPage />, { route: '/games/7/glossary/recommend', path: '/games/:gameId/glossary/recommend' })
const rowOf = (find: string) => screen.getByRole('row', { name: new RegExp(`^\\d+ ${find} `) })

describe('GlossaryRecommendPage', () => {
  it('추천 구절을 지금 번역·문장 수·행 수·표기 비율과 함께 전부 보여 준다', async () => {
    mockFetch(routes())
    render()
    expect(await screen.findByRole('heading', { name: '단어장 추천 — 테스트 게임' })).toBeInTheDocument()
    const row = within(await screen.findByRole('row', { name: /^1 伤害 / }))
    expect(row.getByText('피해')).toBeInTheDocument()
    expect(row.getByText('883')).toBeInTheDocument()
    expect(row.getByText('1,440')).toBeInTheDocument()
    expect(row.getByText('97%')).toBeInTheDocument()
    expect(row.getByText('데미지')).toBeInTheDocument()
    expect(screen.getByText('4 / 4개')).toBeInTheDocument()
    // 지금 번역을 못 찾은 구절도 빼지 않고 보여 준다
    expect(within(rowOf('即可')).getByText('못 찾음')).toBeInTheDocument()
    expect(within(rowOf('敌人')).getByText('용어집에 없음')).toBeInTheDocument()
  })

  it('행을 누르면 표기마다 행 수와 예문이 펼쳐지고, 다시 누르면 접힌다', async () => {
    mockFetch(routes())
    render()
    await userEvent.click(await screen.findByRole('row', { name: /^1 伤害 / }))
    expect(screen.getByText('1,397행')).toBeInTheDocument()
    expect(screen.getByText('30행')).toBeInTheDocument()
    // 예문에서 원문 구절과 그 표기에 표시가 붙는다
    expect(screen.getAllByText('伤害', { selector: 'mark' }).length).toBe(2)
    expect(screen.getByText('피해', { selector: 'mark' })).toBeInTheDocument()
    expect(screen.getByText('데미지', { selector: 'mark' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('row', { name: /^1 伤害 / }))
    expect(screen.queryByText('30행')).not.toBeInTheDocument()
  })

  it('검색어와 필터로 거른다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('row', { name: /^1 伤害 / })
    await userEvent.type(screen.getByLabelText('원문·한국어 검색'), '토템')
    expect(screen.getByText('1 / 4개')).toBeInTheDocument()
    expect(rowOf('图腾')).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('원문·한국어 검색'))

    await userEvent.click(screen.getByLabelText('표기 비율 70% 미만만'))
    expect(screen.getByText('2 / 4개')).toBeInTheDocument()   // 敌人 42%, 即可 못 찾음
    await userEvent.click(screen.getByLabelText('표기 비율 70% 미만만'))

    await userEvent.click(screen.getByLabelText('용어집 번역과 다른 것만'))
    expect(screen.getByText('1 / 4개')).toBeInTheDocument()   // 伤害: 피해 ≠ 데미지
    await userEvent.click(screen.getByLabelText('용어집 번역과 다른 것만'))

    await userEvent.click(screen.getByLabelText('용어집에 없는 것만'))
    expect(screen.getByText('2 / 4개')).toBeInTheDocument()
  })

  it('열 제목을 누르면 그 열로 정렬한다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('row', { name: /^1 伤害 / })
    await userEvent.click(screen.getByRole('columnheader', { name: /그 표기가 든 행/ }))
    const finds = screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[1].textContent)
    expect(finds).toEqual(['即可', '敌人', '伤害', '图腾'])
  })

  it('옛 용어집 번역이 하나도 없는 게임이면 용어집 열과 필터를 숨긴다', async () => {
    mockFetch(routes(recommendations.map((r) => ({ ...r, termKo: null }))))
    render()
    await screen.findByRole('row', { name: /^1 伤害 / })
    expect(screen.queryByRole('columnheader', { name: '용어집 번역' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('용어집에 없는 것만')).not.toBeInTheDocument()
    expect(screen.queryByText('용어집에 없음')).not.toBeInTheDocument()
  })

  it('추천할 구절이 없으면 기준을 알려 준다', async () => {
    mockFetch(routes([]))
    render()
    expect(await screen.findByText(/서로 다른 문장 5개 이상/)).toBeInTheDocument()
  })

  it('표기 갈림 탭으로 바꾸면 갈림 목록을 받고, 추천 필터는 감춘다', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('row', { name: /^1 伤害 / })
    expect(calls.some((c) => c.url.includes('/glossary/variants'))).toBe(false)
    await userEvent.click(screen.getByRole('tab', { name: '표기 갈림' }))
    expect(screen.getByRole('tab', { name: '표기 갈림' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('없음')).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/glossary/variants'))).toBe(true)
    expect(screen.queryByLabelText('표기 비율 70% 미만만')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '추천' }))
    expect(screen.getByRole('row', { name: /^1 伤害 / })).toBeInTheDocument()
  })

  it('탭을 바꿔도 표기 갈림에서 처리한 카드는 처리함으로 남는다', async () => {
    const missing = {
      find: '凯瑞甘', kind: 'MISSING', rows: 5, missingRows: 1, missing: [{ src: '虫群女王·凯瑞甘', ko: '군체의 지배자: 렉시온' }],
      main: { ko: '케리건', rows: 4, spacing: false, examples: [{ src: '凯瑞甘', ko: '케리건' }] }, alts: [],
    }
    const saved = { entry: { id: 1, gameId: 7, kind: 'SRC', matchMode: 'PARTIAL', find: '凯瑞甘', ko: null, note: null, oldKo: null, shared: false, updatedAt: '' }, applied: 0 }
    mockFetch([
      { path: '/games/7/glossary/variants', body: [missing] },
      { method: 'POST', path: '/games/7/glossary/categories', body: {} },
      { method: 'POST', path: '/games/7/glossary', body: saved },
      ...routes(),
    ])
    render()
    await screen.findByRole('row', { name: /^1 伤害 / })
    await userEvent.click(screen.getByRole('tab', { name: '표기 갈림' }))
    await userEvent.click(await screen.findByRole('button', { name: '주 표기 빠짐 1' }))
    await userEvent.click(within(screen.getByRole('article', { name: '凯瑞甘' })).getByRole('button', { name: '단어장에 등록' }))
    expect(await screen.findByText('처리함 · 단어장에 등록, 凯瑞甘 → 케리건')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '추천' }))
    expect(screen.getByRole('row', { name: /^1 伤害 / })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '표기 갈림' }))
    expect(screen.getByText('처리함 · 단어장에 등록, 凯瑞甘 → 케리건')).toBeVisible()
  })

  it('표기 갈림 카드를 분류로 골라 본다', async () => {
    const card = (find: string, ko: string) => ({
      find, kind: 'ALT', rows: 5, missingRows: 0, missing: [],
      main: { ko, rows: 4, spacing: false, examples: [{ src: find, ko }] }, alts: [{ ko: ko + '2', rows: 1, spacing: false, examples: [] }],
    })
    const { calls } = mockFetch([
      { path: '/games/7/glossary/variants', body: [card('蝎子王', '전갈왕'), card('用于', '사용'), card('红色', '빨간색')] },
      { method: 'POST', path: '/games/7/glossary/categories', body: { 蝎子王: 'CHARACTER', 用于: 'FUNCTION' } },
      { method: 'POST', path: '/glossary/literal', body: {} },
      ...routes(),
    ])
    render()
    await screen.findByRole('row', { name: /^1 伤害 / })
    await userEvent.click(screen.getByRole('tab', { name: '표기 갈림' }))
    const group = within(await screen.findByRole('group', { name: '분류' }))
    expect(await group.findByRole('button', { name: '캐릭터 1' })).toBeInTheDocument()
    expect(group.getByRole('button', { name: '미분류 1' })).toBeInTheDocument()
    // 카드가 없는 분류는 칩을 띄우지 않는다
    expect(group.queryByRole('button', { name: /^아이템/ })).not.toBeInTheDocument()
    await userEvent.click(group.getByRole('button', { name: '캐릭터 1' }))
    expect(screen.getByRole('article', { name: '蝎子王' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: '用于' })).not.toBeInTheDocument()
    await userEvent.click(group.getByRole('button', { name: '미분류 1' }))
    expect(screen.getByRole('article', { name: '红色' })).toBeInTheDocument()
    const asked = calls.find((c) => c.url.includes('/glossary/categories'))?.body as { items: { src: string; ko: string }[] }
    expect(asked.items.map((i) => i.src)).toEqual(['蝎子王', '用于', '红色'])
    expect(asked.items[0].ko).toBe('전갈왕')
  })
})
