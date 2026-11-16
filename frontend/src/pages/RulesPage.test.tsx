import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { RulesPage } from './RulesPage'

const game = { id: 7, name: '샘플 게임', projects: 1, memory: 0, lastUploadedAt: '2026-11-01T00:00:00Z', createdAt: '2026-11-01T00:00:00Z' }
const base = { type: 'STYLE', of: '서술식', nf: '개조식', anchor: null, anchors: [], fixed: 0, left: 0, flipped: false, status: 'SUGGESTED', appliedCount: 0, lastEdit: '2026-11-10T00:00:00Z', sameAsHuman: 0.8 }
const effect = { ...base, id: 1, textKind: 'E', verdict: 'RULE', ratio: 0.88, converted: 88, kept: 12, candidates: 4 }
const story = { ...base, id: 2, textKind: 'T', verdict: 'KEEP', ratio: 0.07, converted: 5, kept: 68, candidates: 64 }
const system = { ...base, id: 3, textKind: 'S', verdict: 'DECIDE', ratio: 0.42, converted: 111, kept: 121, candidates: 300 }
const helpNoEdits = { ...base, id: 4, textKind: 'H', verdict: 'INSUFFICIENT', ratio: 0, converted: 0, kept: 0, candidates: 9, sameAsHuman: 0 }
const doneKind = { ...base, id: 5, textKind: 'Q', verdict: 'RULE', ratio: 0.9, converted: 9, kept: 1, candidates: 0 }
const ignored = { ...base, id: 6, textKind: 'I', verdict: 'RULE', ratio: 0.9, converted: 9, kept: 1, candidates: 5, status: 'IGNORED' }
const period = { ...base, id: 8, type: 'PERIOD', of: '줄 끝 마침표', nf: '없음', textKind: null, verdict: 'RULE', ratio: 0, converted: 0, kept: 0, candidates: 2, sameAsHuman: 0 }
const oldWord = { ...base, id: 9, type: 'WORD', of: '엘리트', nf: '에픽', textKind: null, verdict: null, ratio: 0.9, candidates: 3 }
const rules = { learnedAt: '2026-11-13T05:00:00Z', unjudgedRows: 382, estimateUsd: 0.02, rules: [effect, story, system, helpNoEdits, doneKind, ignored, period, oldWord] }
const row = (segmentId: number, rowNo: number, before: string, after: string, extra = {}) =>
  ({ segmentId, projectId: 5, fileName: 'Card.xlsx', sheet: 'Card', rowNo, src: '攻击提升', before, after, complete: true, checked: true, ...extra })
const preview = {
  rows: [row(31, 1, '실패했습니다.', '실패함.'), row(32, 2, '실패했습니다.', '실패함.'), row(33, 5, '실패했습니다.', '실패함.', { fileName: 'Item.xlsx' }),
    row(34, 7, '공격력이 증가합니다.', '공격력이 증가.'),
    row(35, 9, '버튼을 누르십시오', '버튼을 누르십시오', { complete: false, checked: false })],
  excluded: [],
}
const tagRows = [{ segmentId: 51, projectId: 5, sheet: 'Card', rowNo: 4, src: '获得<color>金币</color>', before: '<color=#fff>골드</color>획득', after: '<color=#fff>골드</color> 획득' }]
const routes = (extra: Parameters<typeof mockFetch>[0] = []) => [
  ...extra,
  { path: '/games/7/review/tag-spacing', body: tagRows },
  { method: 'POST', path: '/games/7/review/tag-spacing', body: { applied: 1 } },
  { path: '/rules/1/preview', body: preview },
  { path: '/rules/8/preview', body: { rows: [row(41, 3, '공격력 증가.<br>방어력. 감소.', '공격력 증가<br>방어력. 감소')], excluded: [] } },
  { method: 'POST', path: '/rules/1/apply', body: { applied: 1, skippedChanged: 0 } },
  { method: 'POST', path: '/games/7/rules/relearn', body: rules },
  { path: '/games/7/rules', body: rules },
  { path: '/games/7', body: game },
]
const render = () => renderWithProviders(<RulesPage />, { route: '/games/7/rules', path: '/games/:gameId/rules' })
const openPreview = async () => {
  await userEvent.click(within((await screen.findByText('효과(스킬·버프·장비)')).closest('li')!).getByRole('button', { name: '미리 보기' }))
  return screen.findByRole('dialog', { name: '미리 보기 — 서술식 → 개조식 · 효과(스킬·버프·장비)' })
}

describe('RulesPage', () => {
  it('서술식이 남은 종류는 손 수정 비율과 상관없이 다 보이고, 창을 열면 한 번 찾는다', async () => {
    const { calls } = mockFetch(routes())
    render()
    expect(await screen.findByRole('heading', { name: '교정 규칙 — 샘플 게임' })).toBeInTheDocument()
    expect(await screen.findByText('효과(스킬·버프·장비)')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/games/7/rules/relearn'))).toBe(true)
    // 줄 끝 마침표 빼기는 맨 위, 그다음 남은 행 많은 순: 시스템 300 → 이야기 64 → 도움말 9 → 효과 4
    expect(screen.getAllByRole('listitem').map((li) => li.firstChild?.textContent))
      .toEqual(['줄 끝 마침표 빼기', '태그 띄어쓰기', '시스템 알림', '이야기·배경', '도움말·규칙', '효과(스킬·버프·장비)'])
    const periodRow = screen.getByText('줄 끝 마침표 빼기').closest('li')!
    expect(periodRow).toHaveTextContent('남은 2행')
    expect(periodRow).not.toHaveTextContent('서술식 → 개조식')
    expect(periodRow).not.toHaveTextContent('사람이 바꾼 비율')
    const help = screen.getByText('도움말·규칙').closest('li')!
    expect(help).toHaveTextContent('서술식 남은 9행')
    expect(help).not.toHaveTextContent('사람이 바꾼 비율')                 // 손 수정 없는 종류
    expect(screen.getByText('효과(스킬·버프·장비)').closest('li')).toHaveTextContent('사람이 바꾼 비율 88% (바꿈 88 / 둠 12)')
    expect(screen.queryByText('조건·퀘스트')).not.toBeInTheDocument()      // 남은 행 없음
    expect(screen.queryByText('아이템·획득 안내')).not.toBeInTheDocument()  // 무시함
    expect(screen.queryByText(/엘리트/)).not.toBeInTheDocument()            // 옛 낱말 규칙
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '무시' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /판단하기/ })).toHaveTextContent('382')
  })

  it('미리 보기는 같은 글을 한 줄로 묶고, 묶음을 끄면 그 행들이 뺀 행으로 간다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const dialog = await openPreview()
    expect(within(dialog).getAllByText('실패했습니다.')).toHaveLength(1)
    expect(within(dialog).getByText('×3')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '적용 (4행)' })).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Card.xlsx Card 1행 외 2행' }))
    await userEvent.click(within(dialog).getByRole('button', { name: '적용 (1행)' }))
    const post = calls.find((c) => c.method === 'POST' && c.url.endsWith('/rules/1/apply'))
    expect(post?.body).toEqual({ checked: [{ segmentId: 34, before: '공격력이 증가합니다.', after: '공격력이 증가.' }], unchecked: [31, 32, 33] })
    expect(await screen.findByText('1행 적용')).toBeInTheDocument()
  })

  it('전체 선택을 끄면 다 꺼지고, 몇 개만 골라 적용해도 나머지는 뺀 행으로 가지 않는다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const dialog = await openPreview()
    const all = within(dialog).getByRole('checkbox', { name: '전체 선택' })
    expect(all).toBeChecked()
    await userEvent.click(all)
    expect(all).not.toBeChecked()
    expect(within(dialog).getByRole('button', { name: '적용 (0행)' })).toBeDisabled()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Card.xlsx Card 7행' }))
    expect(all).toBePartiallyChecked()
    await userEvent.click(within(dialog).getByRole('button', { name: '적용 (1행)' }))
    const post = calls.find((c) => c.method === 'POST' && c.url.endsWith('/rules/1/apply'))
    expect(post?.body).toEqual({ checked: [{ segmentId: 34, before: '공격력이 증가합니다.', after: '공격력이 증가.' }], unchecked: [] })
  })

  it('전체 선택을 다시 켜면 기계 변환된 행만 켜진다', async () => {
    mockFetch(routes())
    render()
    const dialog = await openPreview()
    const all = within(dialog).getByRole('checkbox', { name: '전체 선택' })
    await userEvent.click(all)
    await userEvent.click(all)
    expect(within(dialog).getByRole('button', { name: '적용 (4행)' })).toBeInTheDocument()
    expect(within(dialog).getByRole('checkbox', { name: 'Card.xlsx Card 9행' })).not.toBeChecked()
  })

  it('줄 끝 마침표 빼기 미리 보기를 열어 적용한다', async () => {
    const { calls } = mockFetch(routes([{ method: 'POST', path: '/rules/8/apply', body: { applied: 1, skippedChanged: 0 } }]))
    render()
    await userEvent.click(within((await screen.findByText('줄 끝 마침표 빼기')).closest('li')!).getByRole('button', { name: '미리 보기' }))
    const dialog = await screen.findByRole('dialog', { name: '미리 보기 — 줄 끝 마침표 빼기 · 게임 전체' })
    await userEvent.click(within(dialog).getByRole('button', { name: '적용 (1행)' }))
    const post = calls.find((c) => c.method === 'POST' && c.url.endsWith('/rules/8/apply'))
    expect(post?.body).toEqual({ checked: [{ segmentId: 41, before: '공격력 증가.<br>방어력. 감소.', after: '공격력 증가<br>방어력. 감소' }], unchecked: [] })
  })

  it('파일을 거르면 보이는 행만 적용하고, 처음부터 꺼진 행은 빼지 않는다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const dialog = await openPreview()
    expect(within(dialog).getByText(/기계 변환 안 됨 1행/)).toBeInTheDocument()
    await userEvent.selectOptions(within(dialog).getByLabelText('파일'), 'Item.xlsx')
    await userEvent.click(within(dialog).getByRole('button', { name: '적용 (1행)' }))
    const post = calls.find((c) => c.method === 'POST' && c.url.endsWith('/rules/1/apply'))
    expect(post?.body).toEqual({ checked: [{ segmentId: 33, before: '실패했습니다.', after: '실패함.' }], unchecked: [] })
  })

  it('바뀐 글을 직접 고치면 묶음 전체에 그 글로 적용하고, 기계 변환 안 된 줄도 고치면 켜진다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const dialog = await openPreview()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Card.xlsx Card 1행 외 2행 수정' }))
    const box = within(dialog).getByRole('textbox', { name: '바뀐 글' })
    await userEvent.clear(box)
    await userEvent.type(box, '실패')
    await userEvent.click(within(dialog).getByRole('button', { name: '완료' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Card.xlsx Card 9행 수정' }))
    const box2 = within(dialog).getByRole('textbox', { name: '바뀐 글' })
    await userEvent.clear(box2)
    await userEvent.type(box2, '버튼 누르기')
    await userEvent.click(within(dialog).getByRole('button', { name: '완료' }))
    await userEvent.click(within(dialog).getByRole('button', { name: '적용 (5행)' }))
    const post = calls.find((c) => c.method === 'POST' && c.url.endsWith('/rules/1/apply'))
    const checked = (post?.body as { checked: { segmentId: number; after: string }[] }).checked
    expect(checked.filter((c) => [31, 32, 33].includes(c.segmentId)).map((c) => c.after)).toEqual(['실패', '실패', '실패'])
    expect(checked.find((c) => c.segmentId === 35)?.after).toBe('버튼 누르기')
  })

  it('태그 띄어쓰기는 게임 전체를 따로 찾고, 미리 보기에서 고친 뒤 닫으면 다시 센다', async () => {
    const { calls } = mockFetch(routes())
    render()
    const tagRow = (await screen.findByText('태그 띄어쓰기')).closest('li')!
    expect(await within(tagRow).findByText('남은 1행')).toBeInTheDocument()
    const get = calls.find((c) => c.method === 'GET' && c.url.includes('/games/7/review/tag-spacing'))
    expect(get?.url).not.toContain('sheets=')                                    // 범위 없음 = 게임 전체
    await userEvent.click(within(tagRow).getByRole('button', { name: '미리 보기' }))
    const dialog = await screen.findByRole('dialog', { name: '태그 띄어쓰기' })
    expect(within(dialog).getByRole('heading')).toHaveTextContent('태그 띄어쓰기 · 게임 전체')
    await userEvent.click(within(dialog).getByRole('button', { name: '1행 고치기' }))
    expect(await within(dialog).findByText('1행을 고쳤습니다.')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.url.includes('/games/7/review/tag-spacing'))?.body)
      .toEqual({ items: [{ segmentId: 51, before: '<color=#fff>골드</color>획득', after: '<color=#fff>골드</color> 획득' }] })
    const before = calls.filter((c) => c.method === 'GET' && c.url.includes('/tag-spacing')).length
    await userEvent.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await within(tagRow).findByText('남은 1행')
    expect(calls.filter((c) => c.method === 'GET' && c.url.includes('/tag-spacing')).length).toBeGreaterThan(before)
  })

  it('태그 띄어쓰기 검사기가 꺼져 있으면 그 줄에만 알린다', async () => {
    mockFetch(routes([{ path: '/games/7/review/tag-spacing', status: 409, body: { message: '띄어쓰기 검사기가 꺼져 있습니다.' } }]))
    render()
    const tagRow = (await screen.findByText('태그 띄어쓰기')).closest('li')!
    expect(await within(tagRow).findByRole('alert')).toHaveTextContent('띄어쓰기 검사기가 꺼져 있습니다.')
    expect(within(tagRow).getByRole('button', { name: '미리 보기' })).toBeDisabled()
    expect(screen.getByText('시스템 알림')).toBeInTheDocument()
  })
})
