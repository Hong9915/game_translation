import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { resetCommandRuns, startCommandRun } from '../components/workspace/command/commandRuns'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { WorkspacePage } from './WorkspacePage'

/** 게임 전체 리뷰 화면(설계 2026-10-12): /games/:gameId/review. 파일 둘(삼국_텍스트·삼국_UI), 시트 셋. */
const sheet = (configId: number, projectId: number, fileName: string, name: string, total: number) =>
  ({ sheet: name, total, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, configId, projectId, fileName })
const summary = {
  sheets: [sheet(21, 1, '삼국_텍스트.xlsx', 'Skill', 2), sheet(22, 1, '삼국_텍스트.xlsx', 'Item', 1), sheet(31, 2, '삼국_UI.xlsx', 'UI', 1)],
  total: 4, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, unassigned: 4, reviewers: [],
}
const row = (id: number, projectId: number, sheetName: string, rowNo: number) => ({
  id, rowNo, pairIndex: 0, indexLabel: `${sheetName}!${rowNo}`, keyText: `K${id}`, src: `源${id}`, en: null, existingKo: null, fix: `한${id}`, changed: false,
  termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, review3: false, needsCheck: false, confirmed: false, note: null, lastEditor: null,
  lastEditedAt: null, history: [], dupCount: 0, projectId, sheet: sheetName,
})
const rows = [row(10, 1, 'Skill', 2), row(11, 1, 'Skill', 3), row(12, 1, 'Item', 2), row(13, 2, 'UI', 2)]
const me = { id: 1, name: '형준', color: '#1f4b99' }

class FakeEventSource {
  static instances: FakeEventSource[] = []
  url: string
  readyState = 0
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener() {}
  removeEventListener() {}
  close() {}
}

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource)
  localStorage.setItem('gametrans.reviewer', JSON.stringify(me))
  localStorage.setItem('gametrans.workspaceViewSize', 'all')
})
afterEach(() => { FakeEventSource.instances = []; localStorage.removeItem('gametrans.workspaceViewSize'); resetCommandRuns(); vi.restoreAllMocks() })

const routes = () => [
  { path: '/games/3/review/rows/summary', body: summary },
  { path: /\/games\/3\/review\/rows\?sheets=22/, body: { rows: [rows[2]], page: 0, size: 500, total: 1 } },
  { path: /\/games\/3\/review\/rows\?page/, body: { rows, page: 0, size: 500, total: 4 } },
  { path: /\/games\/3$/, body: { id: 3, name: '삼국지M', projects: 2 } },
  { path: '/reviewers', body: [me] },
  { method: 'POST', path: '/games/3/review/presence', body: { reviewers: [] } },
  { method: 'DELETE', path: '/games/3/review/presence', status: 204 },
]
const render = (route = '/games/3/review') => renderWithProviders(<WorkspacePage />, { route, path: '/games/:gameId/review' })

describe('게임 전체 리뷰 화면', () => {
  it('게임의 모든 시트 행을 한 표에 보이고, 파일 / 시트 열은 바로 앞 행과 같으면 옅게 한다', async () => {
    const { calls } = mockFetch(routes())
    render()
    expect(await screen.findByText('K13')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '파일 / 시트' })).toBeInTheDocument()
    const places = screen.getAllByTitle(/삼국_/)
    expect(places.map((p) => p.textContent)).toEqual(['삼국_텍스트.xlsx / Skill', '삼국_텍스트.xlsx / Skill', '삼국_텍스트.xlsx / Item', '삼국_UI.xlsx / UI'])
    expect(places[1].className).toContain('text-muted/50')
    expect(places[2].className).not.toContain('text-muted/50')
    expect(screen.getByText('게임 전체 리뷰')).toBeInTheDocument()
    // 실시간은 게임 스트림 하나, 접속자는 게임 방
    expect(FakeEventSource.instances.map((e) => e.url)).toContain('http://localhost:8080/api/games/3/review/events')
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/games/3/review/presence'))).toBe(true))
  })

  it('시트를 여럿 고르면 내보내기 창에서 "이 시트만"은 꺼지고 전체 파일 zip만 고른다', async () => {
    mockFetch(routes())
    render()
    await userEvent.click(await screen.findByRole('button', { name: '이 시트 내보내기' }))
    const dlg = screen.getByRole('dialog', { name: '내보내기' })
    expect(within(dlg).getByRole('button', { name: /이 시트만/ })).toBeDisabled()
    expect(within(dlg).getByRole('button', { name: /전체 파일 zip/ })).toHaveTextContent('파일 2개')
    await userEvent.click(within(dlg).getByRole('button', { name: '취소' }))

    await userEvent.click(screen.getByRole('button', { name: /시트 고르기/ }))
    const picker = screen.getByRole('dialog', { name: '시트 고르기' })
    expect(within(picker).getByRole('checkbox', { name: /전체/ })).toBeChecked()
    // 파일 하나를 빼면 나머지 시트만
    await userEvent.click(within(picker).getByRole('checkbox', { name: '삼국_UI.xlsx' }))
    expect(within(picker).getByRole('checkbox', { name: '삼국_UI.xlsx UI' })).not.toBeChecked()
    expect(within(picker).getByRole('checkbox', { name: '삼국_텍스트.xlsx Skill' })).toBeChecked()
  })

  it('시트 고르기에서 시트의 시작 행으로 바로 간다 — 앞 시트들의 행 수만큼 건너 그 시트 첫 행의 Fix를 고른다', async () => {
    mockFetch(routes())
    render()
    await screen.findByText('K13')
    await userEvent.click(screen.getByRole('button', { name: /시트 고르기/ }))
    await userEvent.click(within(screen.getByRole('dialog', { name: '시트 고르기' })).getByRole('button', { name: '삼국_텍스트.xlsx Item 시작으로 이동' }))
    expect(screen.queryByRole('dialog', { name: '시트 고르기' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('textbox', { name: '선택한 칸 편집' })).toHaveValue('한12'))
  })

  it('주소의 sheets=로 범위가 정해지고, 시트가 하나면 내보내기 창에서 그 시트만 내보낸다', async () => {
    const blob = new Blob(['x'])
    const { calls } = mockFetch([
      ...routes(),
      { path: '/projects/1/export/preview', body: { outputFileName: '삼국_텍스트_Item_1차수정본.xlsx' } },
      { method: 'POST', path: '/projects/1/export', blob },
    ])
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} }))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render('/games/3/review?sheets=22')
    expect(await screen.findByText('K12')).toBeInTheDocument()
    expect(screen.queryByText('K10')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '이 시트 내보내기' }))
    await userEvent.click(within(screen.getByRole('dialog', { name: '내보내기' })).getByRole('button', { name: /이 시트만/ }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/projects/1/export'))).toBe(true))
    expect(calls.find((c) => c.url.includes('/projects/1/export/preview'))?.url).toContain('single=true')
    expect(calls.find((c) => c.method === 'POST' && c.url.includes('/projects/1/export'))?.body).toMatchObject({ sheet: 'Item' })
  })

  it('일괄 체크는 게임 경로로, 범위는 쿼리로 보낸다', async () => {
    const { calls } = mockFetch([...routes(), { method: 'POST', path: '/games/3/review/marks', body: { updated: 1 } }])
    render('/games/3/review?sheets=22')
    await screen.findByText('K12')
    await userEvent.dblClick(screen.getByRole('columnheader', { name: 'A' }))
    await userEvent.click(await screen.findByRole('button', { name: '모두 체크' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/games/3/review/marks?sheets=22'))).toBe(true))
    expect(calls.find((c) => c.method === 'POST' && c.url.includes('/marks'))?.body).not.toHaveProperty('sheet')
  })

  it('이름이 같아도 다른 범위의 명령 결과는 지금 범위 창에 섞이지 않는다', async () => {
    mockFetch([...routes(), { path: '/games/3/review/command-presets', body: [] }])
    const plan = { summary: null, kind: 'FIX' as const, ops: [{ kind: 'JOSA' as const }], trail: [],
      rows: [{ segmentId: 12, rowNo: 2, src: '源12', before: '한12', after: '다른 범위 결과', by: ['JOSA' as const], warnings: [], mismatch: null, projectId: 1, sheet: 'Item' }] }
    const real = api.review
    vi.spyOn(api, 'review').mockImplementation((t) => ({ ...real(t), commandPlan: () => Promise.resolve(plan) }))
    // Skill·Item 두 시트로 돌린 실행 — 이름은 "시트 2개"
    startCommandRun({ kind: 'game', gameId: 3, sheetIds: [21, 22] }, '시트 2개', { ops: [{ kind: 'JOSA' }] })
    // 지금 범위는 Skill·UI 두 시트 — 이름이 똑같이 "시트 2개"다
    render('/games/3/review?sheets=21,31')
    await userEvent.click(await screen.findByRole('button', { name: /명령 결과 보기/ }))
    expect(await screen.findByRole('dialog', { name: '명령으로 고치기' })).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByRole('checkbox', { name: '다른 범위 결과' })).not.toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '명령으로 고치기' })).toHaveTextContent('명령으로 고치기 · 시트 2개')
  })

  it('두 레인이 있으면 최종 리뷰로 열고 그 시트 id만 보낸다, 일반으로 바꾸면 일반 시트만', async () => {
    const mixed = { ...summary, sheets: [summary.sheets[0], summary.sheets[1], { ...summary.sheets[2], finalReview: true }] }
    const { calls } = mockFetch([
      { path: '/games/3/review/rows/summary', body: mixed },
      { path: /\/games\/3\/review\/rows\?sheets=31/, body: { rows: [{ ...rows[3], finalReview: true }], page: 0, size: 500, total: 1 } },
      { path: /\/games\/3\/review\/rows\?sheets=21%2C22/, body: { rows: rows.slice(0, 3), page: 0, size: 500, total: 3 } },
      ...routes(),
    ])
    render()
    expect(await screen.findByRole('radio', { name: '최종 리뷰' })).toBeChecked()
    await waitFor(() => expect(calls.some((c) => c.url.includes('/review/rows?sheets=31'))).toBe(true))
    // 요약을 받기 전 첫 요청은 sheets= 없이 갈 수 있다 — 서버 기본 레인도 최종 리뷰라 같은 행이다
    expect(await screen.findByRole('columnheader', { name: '최종 수정' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: '일반' }))
    expect(screen.getByTestId('location')).toHaveTextContent('lane=normal')
    await waitFor(() => expect(calls.some((c) => c.url.includes('/review/rows?sheets=21%2C22'))).toBe(true))
    expect(screen.queryByRole('columnheader', { name: '최종 수정' })).toBeNull()
  })

  it('한 레인뿐이면 전환이 없다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('combobox', { name: '필터' })
    expect(screen.queryByRole('radio', { name: '최종 리뷰' })).toBeNull()
  })

  it('옛 주소에 두 레인 시트가 섞여 있어도 400 없이 첫 시트의 레인만 연다', async () => {
    const mixed = { ...summary, sheets: [summary.sheets[0], summary.sheets[1], { ...summary.sheets[2], finalReview: true }] }
    const { calls } = mockFetch([
      { path: '/games/3/review/rows/summary', body: mixed },
      { path: /\/games\/3\/review\/rows\?sheets=21/, body: { rows: [rows[0]], page: 0, size: 500, total: 1 } },
      ...routes(),
    ])
    render('/games/3/review?sheets=21,31')
    expect(await screen.findByRole('radio', { name: '일반' })).toBeChecked()
    await waitFor(() => expect(calls.some((c) => c.url.includes('/review/rows?sheets=21'))).toBe(true))
    // 섞인 범위(21과 31을 함께)는 한 번도 보내지 않는다
    expect(calls.some((c) => /sheets=[^&]*21[^&]*31|sheets=[^&]*31[^&]*21/.test(decodeURIComponent(c.url)))).toBe(false)
  })
})
