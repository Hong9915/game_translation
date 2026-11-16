import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { WorkspacePage } from './WorkspacePage'

const project = { id: 1, fileName: '삼국_텍스트.xlsx', gameId: 3, gameName: '삼국지M', sheetCount: 2, rowCount: 5, uploadedAt: '2026-09-23T00:00:00Z', currentStep: 5, reviewOnly: true }
const summary = { sheets: [{ sheet: 'UI', total: 2, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }, { sheet: 'Skill', total: 1, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }], total: 2, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, unassigned: 2, reviewers: [] }
const rows = [
  { id: 10, rowNo: 2, pairIndex: 0, indexLabel: 'UI!2', keyText: 'UI_CONFIRM', src: '确认', en: 'Confirm', existingKo: '확인', fix: '확인', changed: false, termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, review3: false, needsCheck: false, confirmed: false, note: null, lastEditor: null, lastEditedAt: null, history: [], dupCount: 0 },
  { id: 11, rowNo: 3, pairIndex: 0, indexLabel: 'UI!3', keyText: 'ITEM_1001', src: '混沌圣物', en: 'Chaos Relic', existingKo: '혼돈의 성물', fix: '혼돈의 성물', changed: false, termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, review3: false, needsCheck: false, confirmed: false, note: null, lastEditor: null, lastEditedAt: null, history: [], dupCount: 0 },
]
const saved = { row: { id: 11, indexLabel: 'UI!3', srcText: '混沌圣物', enText: 'Chaos Relic', currentText: '성물', lenKo: 2, lenSrc: 4, flagged: false, history: [], done: false, assignee: null }, propagated: 0 }
const me = { id: 1, name: '형준', color: '#1f4b99' }

// useWorkspaceEvents.test.tsx와 같은 모양 — SSE 이벤트를 실제로 흘려보내야 하는 테스트가 쓴다(그냥 no-op이면 부족).
class FakeEventSource {
  static instances: FakeEventSource[] = []
  listeners: Record<string, ((e: MessageEvent) => void)[]> = {}
  url: string
  readyState = 0
  constructor(url: string) { this.url = url; FakeEventSource.instances.push(this) }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { (this.listeners[name] ??= []).push(fn) }
  removeEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners[name] = (this.listeners[name] ?? []).filter((f) => f !== fn) }
  close() {}
  emit(name: string, data: unknown = {}) { for (const fn of this.listeners[name] ?? []) fn({ data: JSON.stringify(data) } as MessageEvent) }
}

// lib/reviewer.ts의 KEY는 'gametrans.reviewer'다(그냥 'reviewer'가 아니다) — 실제 상수에 맞춰 썼다.
const REVIEWER_KEY = 'gametrans.reviewer'

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource)
  localStorage.setItem(REVIEWER_KEY, JSON.stringify(me))
  localStorage.removeItem('gametrans.workspaceViewSize')
})

afterEach(() => { FakeEventSource.instances = [] })

const render = () => renderWithProviders(<WorkspacePage />, { route: '/review/1', path: '/review/:id' })
const routes = () => [
  { path: '/projects/1', body: project },
  { path: '/review/1/rows/summary', body: summary },
  { path: /\/review\/1\/rows\?sheet=UI/, body: { rows, page: 0, size: 500, total: 2 } },
  { path: /\/review\/1\/rows\?sheet=Skill/, body: { rows: [rows[0]], page: 0, size: 500, total: 1 } },
  { path: '/reviewers', body: [me] },
  { method: 'POST', path: '/presence', body: { reviewers: [] } },
  { method: 'DELETE', path: '/presence', status: 204 },
]

describe('WorkspacePage', () => {
  it('최종 리뷰 시트를 열면 최종 수정 열이 보이고, 일반 시트로 바꾸면 사라진다', async () => {
    const finalSummary = { ...summary, sheets: [{ ...summary.sheets[0], finalReview: true }, summary.sheets[1]] }
    mockFetch([{ path: '/review/1/rows/summary', body: finalSummary }, ...routes()])
    render()
    expect(await screen.findByRole('columnheader', { name: '최종 수정' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: /Skill/ }))
    await waitFor(() => expect(screen.queryByRole('columnheader', { name: '최종 수정' })).toBeNull())
  })

  it('최종 리뷰 시트끼리 옮겨도 최종 수정 필터가 풀리지 않는다', async () => {
    const both = { ...summary, sheets: [{ ...summary.sheets[0], finalReview: true }, { ...summary.sheets[1], finalReview: true }] }
    mockFetch([{ path: '/review/1/rows/summary', body: both }, ...routes()])
    render()
    await screen.findByRole('columnheader', { name: '최종 수정' })
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '필터' }), 'NOT_FINAL_MARK')
    await userEvent.click(screen.getByRole('tab', { name: /Skill/ }))
    await screen.findByRole('columnheader', { name: '최종 수정' })
    expect(screen.getByRole('combobox', { name: '필터' })).toHaveValue('NOT_FINAL_MARK')
  })

  it('조사 확인 필터를 고르면 모두 적용 버튼이 뜨고, 확인 창에서 고르면 시트 전체를 고친다', async () => {
    const josaSummary = { ...summary, josa: 3, sheets: summary.sheets.map((s) => ({ ...s, josa: s.sheet === 'UI' ? 3 : 0 })) }
    const { calls } = mockFetch([
      ...routes().filter((r) => r.path !== '/review/1/rows/summary'),
      { path: '/review/1/rows/summary', body: josaSummary },
      { path: /\/review\/1\/josa\/preview/, body: { counts: { HANGUL: 2, NUMBER: 1, TAG: 0 }, examples: { HANGUL: ['생명를 → 생명을'], NUMBER: ['3로 → 3으로'], TAG: [] } } },
      { method: 'POST', path: '/review/1/josa/apply', body: { changed: 2, skipped: 0 } },
    ])
    render()
    expect(await screen.findByRole('option', { name: '조사 확인 (3)' })).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '필터' }), 'JOSA')
    await userEvent.click(await screen.findByRole('button', { name: '모두 적용 (3)' }))
    await userEvent.click(await screen.findByRole('button', { name: '3곳 고치기' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/review/1/josa/apply')
      && JSON.stringify(c.body) === JSON.stringify({ sheet: 'UI', kinds: ['HANGUL', 'NUMBER'] }))).toBe(true))
  })

  it('태그 띄어쓰기는 교정 규칙 창으로 옮겨서 리뷰 화면에는 버튼이 없다', async () => {
    mockFetch(routes())
    render()
    await screen.findByText('UI_CONFIRM')
    expect(screen.queryByRole('button', { name: /태그 띄어쓰기/ })).not.toBeInTheDocument()
  })

  it('# 칸을 마지막으로 고친 사람 색으로 칠하고 범례를 보이며, # 칸 우클릭으로 그 행의 변경 내역을 연다', async () => {
    const jimin = { id: 1, name: '형준', color: '#1f4b99' }
    const edited = [{ ...rows[0], lastEditor: jimin, lastEditedAt: new Date().toISOString() }, rows[1]]
    const { calls } = mockFetch([
      ...routes().filter((r) => String(r.path) !== String(/\/review\/1\/rows\?sheet=UI/)),
      { path: /\/review\/1\/rows\?sheet=UI/, body: { rows: edited, page: 0, size: 500, total: 2 } },
      { path: '/review/1/rows/10/history', body: [{ version: 1, author: 'HUMAN', text: '확인', createdAt: new Date().toISOString(), reviewer: jimin, activityKind: 'EDIT' }] },
    ])
    render()
    await screen.findByText('UI_CONFIRM')
    expect(screen.getByRole('group', { name: '# 칸 색: 마지막으로 고친 사람' })).toHaveTextContent('형준')
    fireEvent.contextMenu(screen.getByRole('gridcell', { name: '2' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '변경 내역 보기' }))
    expect(await screen.findByRole('dialog', { name: '2행 변경 내역' })).toHaveTextContent('형준· 번역 수정')
    expect(calls.some((c) => c.url.includes('/review/1/rows/10/history'))).toBe(true)
  })

  it('opens the first sheet from the summary and renders its rows', async () => {
    mockFetch(routes())
    render()
    expect(await screen.findByText('UI_CONFIRM')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /UI/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('삼국_텍스트.xlsx')).toBeInTheDocument()
  })

  // 최종 리뷰 Finding 2(설계 §1): 그리드에서 열을 잘못 지정했을 때 정규화로 돌아갈 링크가 파일명 옆에 있어야 한다.
  it('links back to the normalize screen next to the file name', async () => {
    mockFetch(routes())
    render()
    await screen.findByText('UI_CONFIRM')
    expect(screen.getByRole('link', { name: '정규화' })).toHaveAttribute('href', '/normalize/1')
  })

  it('switches sheets from the tabs', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.click(screen.getByRole('tab', { name: /Skill/ }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('rows?sheet=Skill'))).toBe(true))
    await waitFor(() => expect(screen.queryByText('ITEM_1001')).not.toBeInTheDocument())
  })

  it('saves an edited fix cell with the reviewer header and patches the row', async () => {
    const { calls } = mockFetch([...routes(), { method: 'PUT', path: '/segments/11/translation', body: saved }])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '성물{Enter}')
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.includes('/segments/11/translation'))?.body).toEqual({ text: '성물' }))
    expect(calls.find((c) => c.method === 'PUT')?.headers?.['X-Reviewer-Id']).toBe('1')
    await waitFor(() => expect(screen.getByRole('gridcell', { name: '3행 Fix' })).toHaveTextContent('성물'))
    expect(screen.getByRole('gridcell', { name: '3행 Fix' }).className).toContain('fff7d6')
  })

  it('같이 바뀐 행 수를 상태 바에 알린다', async () => {
    const { calls } = mockFetch([...routes(), { method: 'PUT', path: '/segments/11/translation', body: { row: { ...saved.row, currentText: '새 문장' }, propagated: 2 } }])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '새 문장{Enter}')
    expect(await screen.findByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
    // 다른 페이지·다른 시트의 행도 바뀌었을 수 있으니 목록 캐시를 다시 받는다
    await waitFor(() => expect(calls.filter((c) => c.method === 'GET' && c.url.includes('rows?sheet=UI')).length).toBeGreaterThan(1))
  })

  it('같은 건수라도 이전 알림이 사라진 뒤 새 저장이면 다시 알린다 (버그: propagated 값이 같으면 setState가 no-op)', async () => {
    mockFetch([...routes(), { method: 'PUT', path: '/segments/11/translation', body: { row: { ...saved.row, currentText: '새 문장' }, propagated: 2 } }])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '새 문장{Enter}')
    expect(await screen.findByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
    // 첫 알림이 실제로 사라질 때까지 기다린다(StatusBar의 4초 창)
    await waitFor(() => expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument(), { timeout: 4500 })

    // 두 번째 저장도 건수가 똑같이 2행이다 — propagated 상태 값만 보면 지난번과 같아 setState가 no-op일 수 있다.
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box2 = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box2)
    await userEvent.type(box2, '또 다른 문장{Enter}')
    expect(await screen.findByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()
  }, 10000)

  it('알림이 뜬 뒤 전파 없는(propagated:0) 저장이 오면 바로 내려간다 (버그: cleanup만 하고 안 숨김)', async () => {
    mockFetch([
      ...routes(),
      { method: 'PUT', path: '/segments/11/translation', body: { row: { ...saved.row, currentText: '새 문장' }, propagated: 2 } },
      // 다른 행(2행, UI_CONFIRM)을 고치는 다음 저장은 원문이 이 게임에서 하나뿐이라 전파가 없다(propagated: 0).
      { method: 'PUT', path: '/segments/10/translation', body: { row: { id: 10, indexLabel: 'UI!2', srcText: '确认', enText: 'Confirm', currentText: '확인!', lenKo: 3, lenSrc: 2, flagged: false, history: [], done: false, assignee: null }, propagated: 0 } },
    ])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '새 문장{Enter}')
    expect(await screen.findByText('같은 원문 2행도 함께 수정됨')).toBeInTheDocument()

    // 알림이 아직 떠 있는 동안(4초 전) 다른 행을 저장한다 — 그 저장은 전파가 없으므로 떠 있던 알림은 더 이상 최신이 아니다.
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '2행 Fix' }))
    const box2 = screen.getByRole('textbox', { name: '2행 Fix' })
    await userEvent.clear(box2)
    await userEvent.type(box2, '확인!{Enter}')
    await waitFor(() => expect(screen.queryByText('같은 원문 2행도 함께 수정됨')).not.toBeInTheDocument())
  })

  it('1차 칸을 누르면 바로 1차만 체크되고(2차는 그대로) PUT marks를 보낸다', async () => {
    const { calls } = mockFetch([...routes(), { method: 'PUT', path: '/segments/10/marks', body: { ...rows[0], review1: true } }])
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 A' }))
    expect(screen.getByRole('checkbox', { name: '2행 A' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '2행 B' })).not.toBeChecked()
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url.includes('/segments/10/marks'))).toBe(true))
    expect(calls.find((c) => c.url.includes('/segments/10/marks'))?.body).toEqual({ review1: true })
  })

  it('체크 응답이 늦게 와도 캐시의 Fix를 옛 값으로 되돌리지 않는다', async () => {
    mockFetch([...routes(), { method: 'PUT', path: '/segments/10/marks', body: { ...rows[0], fix: '옛 Fix', review1: true, review2: true } }])
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 A' }))
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '2행 B' })).toBeChecked())
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.getByRole('gridcell', { name: '2행 Fix' })).toHaveTextContent('확인')
    expect(screen.queryByText('옛 Fix')).not.toBeInTheDocument()
  })

  it('같은 칸을 빠르게 두 번 누르면 늦게 온 첫 응답이 두 번째 결과를 덮지 않는다', async () => {
    const { fn } = mockFetch(routes())
    const base = fn.getMockImplementation()!
    let n = 0
    fn.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/segments/10/marks')) {
        const on = (JSON.parse(String(init?.body)) as { review1: boolean }).review1
        const reply = new Response(JSON.stringify({ ...rows[0], review1: on, review2: on }), { status: 200, headers: { 'Content-Type': 'application/json' } })
        // 첫 요청(켜기)의 응답이 두 번째(끄기)보다 늦게 온다
        if (n++ === 0) await new Promise((r) => setTimeout(r, 150))
        return reply
      }
      return base(input, init)
    })
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 A' }))
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 A' }))
    await new Promise((r) => setTimeout(r, 250))
    expect(screen.getByRole('checkbox', { name: '2행 A' })).not.toBeChecked()
  })

  it('체크 저장이 실패하면 되돌리고 오류를 보인다', async () => {
    mockFetch([...routes(), { method: 'PUT', path: '/segments/10/marks', status: 500, body: { message: '저장 실패' } }])
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('gridcell', { name: '2행 확인 필요' }))
    expect(await screen.findByText('저장 실패')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '2행 확인 필요' })).not.toBeChecked()
  })

  it('원문 셀 우클릭 메뉴에서 단어장에 추가를 고르면 그 값으로 채워진 패널이 열린다', async () => {
    mockFetch([...routes(), { path: '/games/3/glossary', body: [] }])
    render()
    await screen.findByText('ITEM_1001')
    fireEvent.contextMenu(screen.getByText('混沌圣物'))
    await userEvent.click(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' }))
    expect(await screen.findByLabelText('찾을 문자열')).toHaveValue('混沌圣物')
    expect(screen.getByLabelText('종류')).toHaveValue('SRC')
  })

  it('단어장 패널을 닫으면 셀에서 채워진 값이 지워지고, 토글로 다시 열면 빈 폼이 보인다 (Ruling 39)', async () => {
    mockFetch([...routes(), { path: '/games/3/glossary', body: [] }])
    render()
    await screen.findByText('ITEM_1001')
    fireEvent.contextMenu(screen.getByText('混沌圣物'))
    await userEvent.click(screen.getByRole('menuitem', { name: '단어장에 추가 (중국어)' }))
    expect(await screen.findByLabelText('찾을 문자열')).toHaveValue('混沌圣物')

    await userEvent.click(screen.getByRole('button', { name: '닫기' }))
    await userEvent.click(screen.getByRole('button', { name: '단어장' }))
    expect(await screen.findByLabelText('찾을 문자열')).toHaveValue('')
  })

  it('원문 우클릭 → DeepL 번역은 지시문 창을 거쳐 행 번역 API를 부르고, 받은 행으로 Fix를 바꾼다', async () => {
    const translated = { ...rows[1], fix: '카오스 성물', changed: true }
    const game = { id: 3, name: '삼국지M', projects: 1, memory: 0, lastUploadedAt: null, createdAt: '2026-09-01T00:00:00Z', deeplInstructions: '짧게' }
    const { calls } = mockFetch([...routes(), { method: 'POST', path: '/segments/11/translate', body: translated },
      { method: 'PUT', path: '/games/3/deepl-instructions', body: { ...game, deeplInstructions: '짧게\n게임 용어로' } },
      { path: '/games/3', body: game }])
    render()
    await screen.findByText('ITEM_1001')
    fireEvent.contextMenu(screen.getByText('混沌圣物'))
    await userEvent.click(screen.getByRole('menuitem', { name: 'DeepL 번역' }))
    const box = await screen.findByRole('textbox', { name: 'DeepL 지시문' })
    await waitFor(() => expect(box).toHaveValue('짧게'))
    await userEvent.type(box, '{Enter}게임 용어로')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText('카오스 성물')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'PUT' && c.url.includes('/games/3/deepl-instructions'))!.body).toEqual({ text: '짧게\n게임 용어로' })
    const call = calls.find((c) => c.method === 'POST' && c.url.includes('/segments/11/translate'))!
    expect(call.body).toEqual({})
    expect(call.headers?.['X-Reviewer-Id']).toBe('1')
  })

  it('DeepL 번역이 실패하면 서버 메시지를 오류 배너에 보인다', async () => {
    mockFetch([...routes(), { method: 'POST', path: '/segments/11/translate', status: 400, body: { message: '이 행은 이미 번역 중입니다. 끝난 뒤에 다시 시도하세요.' } }])
    render()
    await screen.findByText('ITEM_1001')
    fireEvent.contextMenu(screen.getByText('混沌圣物'))
    await userEvent.click(screen.getByRole('menuitem', { name: 'DeepL 번역' }))
    await userEvent.click(await screen.findByRole('button', { name: '번역' }))
    expect(await screen.findByText('이 행은 이미 번역 중입니다. 끝난 뒤에 다시 시도하세요.')).toBeInTheDocument()
    expect(screen.getByRole('gridcell', { name: '3행 Fix' })).toHaveTextContent('혼돈의 성물')
  })

  // ×N 칩으로 같은 원문만 모아 볼 때는 표 위에 안내 줄이 뜨고, 안내 줄의 전체 보기나 브라우저 뒤로 가기로 풀린다.
  it('×N 칩을 누르면 같은 원문 안내 줄이 뜨고 주소에 남으며, 전체 보기로 풀린다', async () => {
    const dupRows = [{ ...rows[0], dupCount: 3 }, rows[1]]
    const { calls } = mockFetch([
      { path: /\/review\/1\/rows\?sheet=UI.*q=/, body: { rows: [dupRows[0]], page: 0, size: 500, total: 1 } },
      { path: /\/review\/1\/rows\?sheet=UI/, body: { rows: dupRows, page: 0, size: 500, total: 2 } },
      ...routes(),
    ])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.click(screen.getByRole('button', { name: '×3' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '같은 행 보기' }))
    const banner = await screen.findByRole('status', { name: '같은 원문 모아 보기' })
    expect(banner).toHaveTextContent('确认')
    expect(screen.getByTestId('location')).toHaveTextContent('/review/1?dup=')
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')))).toBe(true))
    await waitFor(() => expect(screen.queryByText('ITEM_1001')).not.toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '전체 보기' }))
    await waitFor(() => expect(screen.queryByRole('status', { name: '같은 원문 모아 보기' })).not.toBeInTheDocument())
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/review\/1$/)
    expect(await screen.findByText('ITEM_1001')).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: '검색' })).toHaveValue('')
  })

  // 모아 보기·검색을 풀면 첫 행이 아니라 들어가기 전에 고르던 칸으로 돌아간다.
  it('모아 보기를 풀면 들어가기 전에 고르던 칸으로 돌아간다', async () => {
    const dupRows = [{ ...rows[0], dupCount: 3 }, rows[1]]
    mockFetch([
      { path: /\/review\/1\/rows\?sheet=UI.*q=/, body: { rows: [dupRows[0]], page: 0, size: 100, total: 1 } },
      { path: /\/review\/1\/rows\?sheet=UI/, body: { rows: dupRows, page: 0, size: 100, total: 2 } },
      ...routes(),
    ])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.click(screen.getByRole('gridcell', { name: '3행 Fix' }))
    await userEvent.click(screen.getByRole('button', { name: '×3' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '같은 행 보기' }))
    await waitFor(() => expect(screen.queryByText('ITEM_1001')).not.toBeInTheDocument())
    await userEvent.click(screen.getByText('UI_CONFIRM'))   // 모아 보기 안에서 다른 칸을 골라도
    await userEvent.click(screen.getByRole('button', { name: '전체 보기' }))
    await screen.findByText('ITEM_1001')
    await waitFor(() => expect(screen.getByRole('region', { name: '선택한 칸 내용' })).toHaveTextContent('3행 · 수정 한국어 (Fix)'))
  })

  it('검색어를 지우면 검색하기 전에 고르던 칸으로 돌아간다', async () => {
    mockFetch([
      { path: /\/review\/1\/rows\?sheet=UI.*q=ITEM/, body: { rows: [rows[1]], page: 0, size: 100, total: 1 } },
      ...routes(),
    ])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.click(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('searchbox', { name: '검색' })
    await userEvent.type(box, 'ITEM')
    await waitFor(() => expect(screen.queryByText('UI_CONFIRM')).not.toBeInTheDocument())
    await userEvent.click(await screen.findByText('ITEM_1001'))
    await userEvent.clear(box)
    await screen.findByRole('gridcell', { name: '2행 Fix' })
    await waitFor(() => expect(screen.getByRole('region', { name: '선택한 칸 내용' })).toHaveTextContent('3행 · 수정 한국어 (Fix)'))
  })

  it('검색할 칸을 기존 한국어로 고르면 기존 한국어에서만 찾는다(qIn=EXISTING_KO)', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByText('ITEM_1001')
    // 검색어가 없을 때는 qIn을 보내지 않는다
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '검색할 칸' }), 'EXISTING_KO')
    expect(calls.some((c) => c.url.includes('qIn='))).toBe(false)
    const box = screen.getByRole('searchbox', { name: '검색' })
    expect(box).toHaveAttribute('placeholder', '기존 한국어에서 검색')
    await userEvent.type(box, '심판')
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('심판')) && c.url.includes('qIn=EXISTING_KO'))).toBe(true))
  })

  it('모아 보기는 찾을 칸을 원문 그대로로 바꾼다 (기존 한국어를 골라 두었어도)', async () => {
    const dupRows = [{ ...rows[0], dupCount: 3 }, rows[1]]
    const { calls } = mockFetch([
      { path: /\/review\/1\/rows\?sheet=UI.*q=/, body: { rows: [dupRows[0]], page: 0, size: 500, total: 1 } },
      { path: /\/review\/1\/rows\?sheet=UI/, body: { rows: dupRows, page: 0, size: 500, total: 2 } },
      ...routes(),
    ])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '검색할 칸' }), 'EXISTING_KO')
    await userEvent.click(screen.getByRole('button', { name: '×3' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '같은 행 보기' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')))).toBe(true))
    expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')) && c.url.includes('qIn=EXISTING_KO'))).toBe(false)
    expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')) && c.url.includes('qIn=SRC_EXACT'))).toBe(true)
    expect(screen.getByRole('combobox', { name: '검색할 칸' })).toHaveValue('SRC_EXACT')
  })

  // 뒤로 가기는 주소의 ?dup=이 바뀌는 것 — 주소에 dup이 있으면 모아 보기, 없어지면 푼다.
  it('주소에 dup이 있으면 그 원문으로 모아 보기를 연다 (뒤로 가기·새로 고침)', async () => {
    const { calls } = mockFetch(routes())
    renderWithProviders(<WorkspacePage />, { route: '/review/1?dup=' + encodeURIComponent('确认'), path: '/review/:id' })
    expect(await screen.findByRole('status', { name: '같은 원문 모아 보기' })).toHaveTextContent('确认')
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')))).toBe(true))
  })

  // 모아 보기는 원문 포함 검색이 아니라 원문이 글자 그대로 같은 행만 — 원문을 품은 긴 문장이나 Fix에만 든 행은 안 나온다
  it('모아 보기는 원문이 글자 그대로 같은 행만 찾고, 다른 칸을 고르면 모아 보기가 풀린다', async () => {
    const { calls } = mockFetch(routes())
    renderWithProviders(<WorkspacePage />, { route: '/review/1?dup=' + encodeURIComponent('确认'), path: '/review/:id' })
    await screen.findByRole('status', { name: '같은 원문 모아 보기' })
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')) && c.url.includes('qIn=SRC_EXACT'))).toBe(true))
    const field = screen.getByRole('combobox', { name: '검색할 칸' })
    expect(field).toHaveValue('SRC_EXACT')
    await userEvent.selectOptions(field, 'DEFAULT')
    await waitFor(() => expect(screen.queryByRole('status', { name: '같은 원문 모아 보기' })).not.toBeInTheDocument())
    expect(screen.getByRole('combobox', { name: '검색할 칸' })).toHaveValue('DEFAULT')
    expect(screen.queryByRole('option', { name: '원문 그대로' })).not.toBeInTheDocument()
  })

  // 칩 메뉴의 "포함된 전체 행 보기"는 원문에 그 글이 든 행을 모두 — 주소에 dupIn=contains로 남는다
  it('포함된 전체 행 보기는 원문 포함으로 찾고 주소에 남는다', async () => {
    const dupRows = [{ ...rows[0], dupCount: 3 }, rows[1]]
    const { calls } = mockFetch([
      { path: /\/review\/1\/rows\?sheet=UI.*q=/, body: { rows: [dupRows[0]], page: 0, size: 500, total: 1 } },
      { path: /\/review\/1\/rows\?sheet=UI/, body: { rows: dupRows, page: 0, size: 500, total: 2 } },
      ...routes(),
    ])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.click(screen.getByRole('button', { name: '×3' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '포함된 전체 행 보기' }))
    expect(await screen.findByRole('status', { name: '원문 포함 모아 보기' })).toHaveTextContent('确认')
    expect(screen.getByTestId('location')).toHaveTextContent('dupIn=contains')
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=' + encodeURIComponent('确认')) && c.url.includes('qIn=SRC_CONTAINS'))).toBe(true))
    expect(screen.getByRole('combobox', { name: '검색할 칸' })).toHaveValue('SRC_CONTAINS')
    await userEvent.click(screen.getByRole('button', { name: '전체 보기' }))
    await waitFor(() => expect(screen.queryByRole('status', { name: '원문 포함 모아 보기' })).not.toBeInTheDocument())
    expect(screen.getByRole('combobox', { name: '검색할 칸' })).toHaveValue('DEFAULT')
  })

  it('모아 보기 중에 검색어를 직접 바꾸면 안내 줄이 사라지고 주소에서 dup이 빠진다', async () => {
    mockFetch(routes())
    renderWithProviders(<WorkspacePage />, { route: '/review/1?dup=' + encodeURIComponent('确认'), path: '/review/:id' })
    await screen.findByRole('status', { name: '같은 원문 모아 보기' })
    const box = screen.getByRole('searchbox', { name: '검색' })
    await userEvent.clear(box)
    await userEvent.type(box, 'ITEM')
    await waitFor(() => expect(screen.queryByRole('status', { name: '같은 원문 모아 보기' })).not.toBeInTheDocument())
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/review\/1$/)
    expect(box).toHaveValue('ITEM')
  })

  it('exports the workbook with the preview file name', async () => {
    const blob = new Blob(['x'])
    const { calls } = mockFetch([...routes(), { path: '/export/preview', body: { outputFileName: '삼국_텍스트_1차수정본.xlsx' } }, { method: 'POST', path: '/projects/1/export', blob }])
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() })   // jsdom에는 없다
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})   // jsdom 네비게이션 경고 방지
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('button', { name: '내보내기' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/projects/1/export'))).toBe(true))
  })

  it('asks for a name when no reviewer is stored', async () => {
    localStorage.removeItem(REVIEWER_KEY)
    mockFetch(routes())
    render()
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('keeps the typed text visible with a red border and marks the status bar on a failed save (설계 §5)', async () => {
    mockFetch([...routes(), { method: 'PUT', path: '/segments/11/translation', status: 500, body: { message: '저장 실패' } }])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '내가 입력한 값{Enter}')
    const cell = await screen.findByRole('gridcell', { name: '3행 Fix' })
    await waitFor(() => expect(cell).toHaveTextContent('내가 입력한 값'))
    expect(cell.className).toContain('border-red-500')
    expect(screen.getByText('● 저장 안 됨')).toBeInTheDocument()
  })

  it('drops the failed-save marker when the same row changes elsewhere (SSE), and a later edit starts from that new value', async () => {
    const { calls } = mockFetch([...routes(), { method: 'PUT', path: '/segments/11/translation', status: 500, body: { message: '저장 실패' } }])
    render()
    await screen.findByText('ITEM_1001')
    await userEvent.dblClick(screen.getByRole('gridcell', { name: '3행 Fix' }))
    const box = screen.getByRole('textbox', { name: '3행 Fix' })
    await userEvent.clear(box)
    await userEvent.type(box, '내가 입력한 값{Enter}')
    const cell = await screen.findByRole('gridcell', { name: '3행 Fix' })
    await waitFor(() => expect(cell).toHaveTextContent('내가 입력한 값'))
    expect(cell.className).toContain('border-red-500')

    // 다른 검토자(또는 다른 탭)가 같은 행을 먼저 저장했다는 SSE 이벤트가 온다
    const es = FakeEventSource.instances[0]
    const seg = { id: 11, indexLabel: 'UI!3', srcText: '混沌圣物', enText: 'Chaos Relic', currentText: '다른 사람 값', lenKo: 5, lenSrc: 4, flagged: false, history: [] }
    act(() => { es.emit('review', { type: 'segment-updated', by: 99, row: seg }) })

    await waitFor(() => expect(cell).toHaveTextContent('다른 사람 값'))
    expect(cell.className).not.toContain('border-red-500')

    // 재편집하면 옛 실패 텍스트가 아니라 방금 들어온 서버 값에서 시작한다
    await userEvent.dblClick(cell)
    const box2 = screen.getByRole('textbox', { name: '3행 Fix' })
    expect(box2).toHaveValue('다른 사람 값')
    await userEvent.type(box2, '!{Enter}')
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url.includes('/segments/11/translation') && (c.body as { text: string }).text === '다른 사람 값!')).toBe(true))
  })

  describe('쪽 넘기기 보기', () => {
    const bigSummary = { sheets: [{ sheet: 'UI', total: 250, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }, { sheet: 'Skill', total: 1, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }], total: 251, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, unassigned: 251, reviewers: [] }
    const pageRows = (page: number, size: number) => Array.from({ length: Math.min(size, 250 - page * size) }, (_, i) => ({ ...rows[0], id: page * size + i, rowNo: page * size + i + 2, keyText: `R${page * size + i}` }))
    const bigRoutes = () => [
      ...routes().filter((r) => r.path !== '/review/1/rows/summary' && String(r.path) !== String(/\/review\/1\/rows\?sheet=UI/)),
      { path: '/review/1/rows/summary', body: bigSummary },
      ...[0, 1, 2].flatMap((p) => [50, 100, 500].map((s) => ({ path: new RegExp(`/review/1/rows\\?sheet=UI&page=${p}&size=${s}(&|$)`), body: { rows: pageRows(p, s), page: p, size: s, total: 250 } }))),
    ]

    it('처음에는 100행씩 받아 첫 쪽을 보이고, 다음 쪽 버튼으로 다음 100행을 받는다', async () => {
      const { calls } = mockFetch(bigRoutes())
      render()
      expect(await screen.findByText('R0')).toBeInTheDocument()
      expect(calls.some((c) => c.url.includes('rows?sheet=UI&page=0&size=100'))).toBe(true)
      expect(screen.getByText('1–100 / 250행')).toBeInTheDocument()
      expect(screen.queryByText('R100')).not.toBeInTheDocument()
      // 쪽 이동은 표 위 도구줄(필터·보기와 같은 줄)에 있다
      expect(screen.getByRole('combobox', { name: '필터' }).parentElement).toContainElement(screen.getByRole('button', { name: '다음 쪽' }))
      await userEvent.click(screen.getByRole('button', { name: '다음 쪽' }))
      expect(await screen.findByText('R100')).toBeInTheDocument()
      expect(screen.queryByText('R0')).not.toBeInTheDocument()
      expect(screen.getByText('101–200 / 250행')).toBeInTheDocument()
    })

    it('50행을 고르면 50행씩 받고, 고른 값은 다음에 열 때도 기억한다', async () => {
      const { calls } = mockFetch(bigRoutes())
      const { unmount } = render()
      await screen.findByText('R0')
      await userEvent.click(screen.getByRole('button', { name: '50행' }))
      await waitFor(() => expect(calls.some((c) => c.url.includes('rows?sheet=UI&page=0&size=50'))).toBe(true))
      await waitFor(() => expect(screen.queryByText('R50')).not.toBeInTheDocument())
      expect(screen.getByText('1–50 / 250행')).toBeInTheDocument()
      unmount()
      render()
      await screen.findByText('R0')
      expect(screen.getByRole('button', { name: '50행' })).toHaveAttribute('aria-pressed', 'true')
    })

    it('전체를 고르면 쪽 바를 숨기고 예전처럼 500행씩 스크롤로 받는다', async () => {
      const { calls } = mockFetch(bigRoutes())
      render()
      await screen.findByText('R0')
      await userEvent.click(screen.getByRole('button', { name: '전체' }))
      await waitFor(() => expect(calls.some((c) => c.url.includes('rows?sheet=UI&page=0&size=500'))).toBe(true))
      await waitFor(() => expect(screen.queryByRole('button', { name: '다음 쪽' })).not.toBeInTheDocument())
      // 전체 보기는 한 표에 250행 전부를 둔다(쪽 바 없이 1쪽 범위를 넘는 행도 같은 표에 있다)
      expect(screen.getByRole('grid')).toHaveAttribute('aria-rowcount', '250')
    })

    it('머리글 더블클릭 일괄 해제는 지금 쪽만 대상으로 보낸다', async () => {
      const { calls } = mockFetch([...bigRoutes(), { method: 'POST', path: '/review/1/marks', body: { updated: 100 } }])
      render()
      expect(await screen.findByText('R0')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: '다음 쪽' }))
      expect(await screen.findByText('R100')).toBeInTheDocument()
      fireEvent.doubleClick(screen.getByRole('columnheader', { name: 'A' }))
      expect(screen.getByRole('dialog', { name: 'A 일괄 체크' })).toHaveTextContent('이 보기의 100행')
      await userEvent.click(screen.getByRole('button', { name: '모두 해제' }))
      await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/review/1/marks'))).toBe(true))
      // 서버가 쪽을 다시 계산하지 않게 화면에 보이는 100행의 id를 그대로 보낸다
      const body = calls.find((c) => c.url.includes('/review/1/marks'))?.body as { sheet: string; ids: number[]; review1: boolean }
      expect(body).toMatchObject({ sheet: 'UI', review1: false })
      expect(body).not.toHaveProperty('page')
      expect(body.ids).toHaveLength(100)
      expect(body.ids[0]).toBe(100)
    })

    it('시트를 바꾸면 첫 쪽으로 돌아간다', async () => {
      const { calls } = mockFetch(bigRoutes())
      render()
      await screen.findByText('R0')
      await userEvent.click(screen.getByRole('button', { name: '다음 쪽' }))
      await screen.findByText('R100')
      await userEvent.click(screen.getByRole('tab', { name: /Skill/ }))
      await waitFor(() => expect(calls.some((c) => c.url.includes('rows?sheet=Skill&page=0&size=100'))).toBe(true))
      expect(calls.some((c) => c.url.includes('rows?sheet=Skill&page=1'))).toBe(false)
    })
  })

  it('never shows page-0 rows under page-2 row numbers while a scroll jump is loading (regression: Finding 1 windowing)', async () => {
    localStorage.setItem('gametrans.workspaceViewSize', 'all')   // 이 회귀는 전체 스크롤 보기의 창 계산이다
    const bigSummary = { sheets: [{ sheet: 'UI', total: 1200, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0 }], total: 1200, changed: 0, review1: 0, review2: 0, review3: 0, needsCheck: 0, confirmed: 0, josa: 0, josaPending: 0, unassigned: 1200, reviewers: [] }
    const page0Rows = Array.from({ length: 500 }, (_, i) => ({ ...rows[0], id: i, rowNo: i + 1, keyText: `P0-${i}` }))
    const page2Rows = Array.from({ length: 200 }, (_, i) => ({ ...rows[0], id: 1000 + i, rowNo: 1001 + i, keyText: `P2-${i}` }))
    let resolvePage2: () => void = () => {}
    const calls: { method: string; url: string }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()
      calls.push({ method, url })
      if (method === 'GET' && url.includes('/projects/1') && !url.includes('export')) return new Response(JSON.stringify(project), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url.includes('/review/1/rows/summary')) return new Response(JSON.stringify(bigSummary), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url.includes('/reviewers')) return new Response(JSON.stringify([me]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (method === 'POST' && url.includes('/presence')) return new Response(JSON.stringify({ reviewers: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (method === 'DELETE' && url.includes('/presence')) return new Response(null, { status: 204 })
      if (url.includes('/review/1/rows') && url.includes('page=0')) return new Response(JSON.stringify({ rows: page0Rows, page: 0, size: 500, total: 1200 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url.includes('/review/1/rows') && url.includes('page=2')) {
        // page 2 요청을 일부러 안 끝낸 채로 둔다 — 그 사이 placeholderData(옛 page 0 응답)가 어디에 얹히는지가 이 테스트의 요점
        return new Promise<Response>((resolve) => { resolvePage2 = () => resolve(new Response(JSON.stringify({ rows: page2Rows, page: 2, size: 500, total: 1200 }), { status: 200, headers: { 'Content-Type': 'application/json' } })) })
      }
      return new Response(JSON.stringify({ message: `no mock for ${method} ${url}` }), { status: 404 })
    }))
    render()
    await screen.findByText('P0-0')
    const grid = screen.getByRole('grid')
    fireEvent.scroll(grid, { target: { scrollTop: 28600 } })   // page 2대(1050~1189행) 쪽으로 스크롤 점프
    await waitFor(() => expect(calls.some((c) => c.url.includes('page=2'))).toBe(true))
    // page 2 응답이 아직 안 왔다 — page 0의 옛 데이터가 지금 보이는 구간(1050~1189행)에 얹히면 안 된다
    expect(screen.queryAllByText(/^P0-/).length).toBe(0)
    resolvePage2()
    await screen.findByText('P2-50')
  })

  // mockFetch는 앞에서부터 includes로 찾으므로 '/projects/1'(프로젝트)보다 '/projects/1/activity'를 앞에 둔다.
  const activityRoutes = () => [
    { path: '/projects/1/activity', body: { items: [
      { id: 30, kind: 'EDIT', reviewer: me, createdAt: new Date().toISOString(), rowCount: 1, sheet: 'Skill',
        primary: { segmentId: 10, projectId: 1, sheet: 'Skill', rowNo: 2, ordinal: 0, before: '확인', after: '확인!' }, detail: { segmentId: 10 } },
    ] } },
    { path: '/games/3/glossary', body: [] },
    ...routes(),
  ]

  it('변경 내역과 단어장 패널은 하나만 열린다', async () => {
    mockFetch(activityRoutes())
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('button', { name: '단어장' }))
    expect(await screen.findByRole('heading', { name: '단어장' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '변경 내역' }))
    expect(await screen.findByRole('heading', { name: '변경 내역' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '단어장' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '변경 내역' }))
    // 닫힐 때는 폭 전환이 끝날 때까지(200ms) 남아 있다가 사라진다
    await waitFor(() => expect(screen.queryByRole('heading', { name: '변경 내역' })).toBeNull())
  })

  // 고른 칸은 WorkspaceGrid가 outline-[#107c41] 클래스로 표시한다(aria 속성이 아니다).
  it('변경 내역의 행을 누르면 그 시트로 가서 그 행의 Fix 칸을 고른다', async () => {
    const { calls } = mockFetch(activityRoutes())
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('button', { name: '변경 내역' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Skill 2행' }))
    await waitFor(() => expect(screen.getByRole('tab', { name: /Skill/ })).toHaveAttribute('aria-selected', 'true'))
    await waitFor(() => expect(calls.some((c) => c.url.includes('rows?sheet=Skill'))).toBe(true))
    await waitFor(() => expect(screen.getByRole('gridcell', { name: '2행 Fix' }).className).toContain('107c41'))
    // 패널은 열린 채 남는다
    expect(screen.getByRole('heading', { name: '변경 내역' })).toBeInTheDocument()
  })

  /** 리뷰 finding 3 검증: 시트가 바뀌면 옛 시트 행이 placeholder로 남지 않는다(isPlaceholderData false) — 순번이 옛 목록 밖이어도 새 시트 행을 고른다. */
  it('옛 시트의 placeholder 행이 남아 있어도 새 시트 행이 오면 그 행을 고른다', async () => {
    const skill = Array.from({ length: 8 }, (_, i) => ({ ...rows[0], id: 100 + i, rowNo: i + 2, indexLabel: `Skill!${i + 2}`, keyText: `K${i + 2}` }))
    mockFetch([
      { path: '/projects/1/activity', body: { items: [
        { id: 32, kind: 'EDIT', reviewer: me, createdAt: new Date().toISOString(), rowCount: 1, sheet: 'Skill',
          primary: { segmentId: 105, projectId: 1, sheet: 'Skill', rowNo: 7, ordinal: 5, before: null, after: 'x' }, detail: { segmentId: 105 } },
      ] } },
      { path: /\/review\/1\/rows\?sheet=Skill/, body: { rows: skill, page: 0, size: 100, total: 8 } },
      ...activityRoutes().filter((r) => !(typeof r.path === 'string' && r.path.includes('activity')) && !(r.path instanceof RegExp && r.path.source.includes('Skill'))),
    ])
    localStorage.setItem('gametrans.workspaceViewSize', 'all')
    render()
    await screen.findByText('UI_CONFIRM')   // UI 시트는 2행뿐 — 순번 5는 옛 목록 밖이다
    await userEvent.click(screen.getByRole('button', { name: '변경 내역' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Skill 7행' }))
    await waitFor(() => expect(screen.getByRole('gridcell', { name: '7행 Fix' }).className).toContain('107c41'))
  })

  /** Review Focus 5: 쪽 보기(50행)에서 순번 120은 셋째 쪽(2)의 20번째 행이다. */
  it('jumps to a row on another page', async () => {
    localStorage.setItem('gametrans.workspaceViewSize', '50')
    const many = Array.from({ length: 130 }, (_, i) => ({ ...rows[0], id: 100 + i, rowNo: i + 2, indexLabel: `Skill!${i + 2}`, keyText: `K${i + 2}` }))
    const page = (n: number) => ({ rows: many.slice(n * 50, n * 50 + 50), page: n, size: 50, total: 130 })
    const { calls } = mockFetch([
      { path: '/projects/1/activity', body: { items: [
        { id: 31, kind: 'EDIT', reviewer: me, createdAt: new Date().toISOString(), rowCount: 1, sheet: 'Skill',
          primary: { segmentId: 220, projectId: 1, sheet: 'Skill', rowNo: 122, ordinal: 120, before: null, after: 'x' }, detail: { segmentId: 220 } },
      ] } },
      { path: /\/review\/1\/rows\?sheet=Skill&page=2/, body: page(2) },
      { path: /\/review\/1\/rows\?sheet=Skill&page=0/, body: page(0) },
      ...activityRoutes().filter((r) => !(typeof r.path === 'string' && r.path.includes('activity')) && !(r.path instanceof RegExp && r.path.source.includes('Skill'))),
    ])
    render()
    await screen.findByText('UI_CONFIRM')
    await userEvent.click(screen.getByRole('button', { name: '변경 내역' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Skill 122행' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('sheet=Skill&page=2'))).toBe(true))
    await waitFor(() => expect(screen.getByRole('gridcell', { name: '122행 Fix' }).className).toContain('107c41'))
  })

  describe('되돌리기·다시 실행·Fix 열 전체 복사', () => {
    const editFix = async (rowNo: number, text: string) => {
      await userEvent.dblClick(screen.getByRole('gridcell', { name: `${rowNo}행 Fix` }))
      const box = screen.getByRole('textbox', { name: `${rowNo}행 Fix` })
      await userEvent.clear(box)
      await userEvent.type(box, `${text}{Enter}`)
    }
    const undoCalls = (calls: { method: string; url: string; body: unknown }[]) => calls.filter((c) => c.method === 'POST' && c.url.includes('/activity/undo'))

    it('undo after save calls activity undo with the save\'s activityId', async () => {
      const { calls } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, activityId: 77 } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 1, skipped: 0, activityIds: [90] } }])
      render()
      await screen.findByText('ITEM_1001')
      await editFix(3, '성물')
      await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
      await screen.findByText('저장됨')
      fireEvent.keyDown(screen.getByRole('grid'), { key: 'z', code: 'KeyZ', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(1))
      expect(undoCalls(calls)[0].body).toEqual({ activityIds: [77] })
      expect(await screen.findByText('수정 되돌림 · 1행')).toBeInTheDocument()
      // 목록을 다시 받는다
      await waitFor(() => expect(calls.filter((c) => c.method === 'GET' && c.url.includes('rows?sheet=UI')).length).toBeGreaterThan(1))
    })

    it('redo calls undo with the returned ids', async () => {
      const { calls } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, activityId: 77 } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 1, skipped: 1, activityIds: [90] } }])
      render()
      await screen.findByText('ITEM_1001')
      await editFix(3, '성물')
      await screen.findByText('저장됨')
      const grid = screen.getByRole('grid')
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      expect(await screen.findByText('수정 되돌림 · 1행 · 1행은 그 사이 바뀌었거나 확정이라 건너뜀')).toBeInTheDocument()
      fireEvent.keyDown(grid, { key: 'y', code: 'KeyY', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(2))
      expect(undoCalls(calls)[1].body).toEqual({ activityIds: [90] })
      expect(await screen.findByText(/^다시 실행 · 1행/)).toBeInTheDocument()
    })

    it('ignores Ctrl+Z while an undo request is in flight', async () => {
      const { calls } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, activityId: 77 } },
        { method: 'PUT', path: '/segments/10/translation', body: { ...saved, row: { ...saved.row, id: 10 }, activityId: 78 } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 1, skipped: 0, activityIds: [90] } }])
      render()
      await screen.findByText('ITEM_1001')
      await editFix(3, '성물')
      await editFix(2, '확인!')
      await waitFor(() => expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(2))
      await screen.findByText('저장됨')
      const grid = screen.getByRole('grid')
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      await screen.findByText('수정 되돌림 · 1행')
      expect(undoCalls(calls)).toHaveLength(1)
      expect(undoCalls(calls)[0].body).toEqual({ activityIds: [78] })
    })

    it('a save during an in-flight undo keeps redo empty and stays on top', async () => {
      const { calls, fn } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, activityId: 77 } },
        { method: 'PUT', path: '/segments/10/translation', body: { ...saved, row: { ...saved.row, id: 10 }, activityId: 78 } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 1, skipped: 0, activityIds: [90] } }])
      // 되돌리기 응답을 붙잡아 두고, 그동안 다른 행을 저장한다
      let release = () => {}
      const gate = new Promise<void>((r) => { release = r })
      const orig = fn.getMockImplementation()!
      fn.mockImplementation(async (input, init) => {
        if (String(input).includes('/activity/undo') && undoCalls(calls).length === 0) { const res = orig(input, init); await gate; return res }
        return orig(input, init)
      })
      render()
      await screen.findByText('ITEM_1001')
      await editFix(3, '성물')
      await screen.findByText('저장됨')
      const grid = screen.getByRole('grid')
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(1))
      await editFix(2, '확인!')
      await waitFor(() => expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(2))
      await screen.findByText('저장됨')
      await act(async () => { release() })
      expect(await screen.findByText('수정 되돌림 · 1행')).toBeInTheDocument()
      fireEvent.keyDown(grid, { key: 'y', code: 'KeyY', ctrlKey: true })
      expect(await screen.findByText('다시 실행할 것이 없습니다')).toBeInTheDocument()
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(2))
      expect(undoCalls(calls)[1].body).toEqual({ activityIds: [78] })
    })

    it('원문 범위 우클릭 GPT 번역은 여러 행 창을 열고, 번역 결과를 바로 반영하며 Ctrl+Z 한 번에 되돌린다', async () => {
      const game = { id: 3, name: '삼국지M', deeplInstructions: '', gptTranslateBase: '기본 지시 글', gptTranslateExtra: null, gptPolishBase: '', gptPolishExtra: null }
      const { calls } = mockFetch([...routes(), { path: '/games/3', body: game },
        { method: 'POST', path: '/segments/translate-batch', body: { rows: [{ ...rows[0], fix: '확인함' }, { ...rows[1], fix: '카오스 성물' }], skipped: [], activityIds: { 1: 300 } } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 2, skipped: 0, activityIds: [301] } }])
      render()
      await screen.findByText('ITEM_1001')
      fireEvent.mouseDown(screen.getByText('确认'), { button: 0 })
      fireEvent.mouseDown(screen.getByText('混沌圣物'), { button: 0, shiftKey: true })
      fireEvent.contextMenu(screen.getByText('确认'))
      await userEvent.click(screen.getByRole('menuitem', { name: 'GPT 번역 (2행)' }))
      expect(await screen.findByRole('heading', { name: 'GPT 번역 (2행)' })).toBeInTheDocument()
      expect(await screen.findByText('기본 지시 글')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: '번역' }))
      expect(await screen.findByText('카오스 성물')).toBeInTheDocument()
      expect(calls.find((c) => c.url.includes('/segments/translate-batch'))!.body).toEqual({ segmentIds: [10, 11], engine: 'GPT' })
      expect(await screen.findByText('번역 2행 · 건너뜀 0행')).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'GPT 번역 (2행)' })).toBeNull()
      fireEvent.keyDown(screen.getByRole('grid'), { key: 'z', code: 'KeyZ', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(1))
      expect(undoCalls(calls)[0].body).toEqual({ activityIds: [300] })
      expect(await screen.findByText('GPT 번역 2행 되돌림 · 2행')).toBeInTheDocument()
    })

    it('Fix 범위 우클릭 GPT로 다듬기는 전/후 창을 열고, 넣은 뒤 목록을 다시 읽으며 Ctrl+Z 한 번에 되돌린다', async () => {
      const game = { id: 3, name: '삼국지M', deeplInstructions: '', gptTranslateBase: '', gptTranslateExtra: null, gptPolishBase: '다듬기 기본 지시', gptPolishExtra: null }
      const { calls } = mockFetch([...routes(), { path: '/games/3', body: game },
        { method: 'POST', path: '/segments/polish', body: { items: [{ segmentId: 11, before: '혼돈의 성물', after: '카오스 성물', reason: '용어' }], kept: 1, skipped: [] } },
        { method: 'POST', path: '/segments/save-batch', body: { saved: 1, skipped: 0, propagated: 2, activityIds: [400] } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 1, skipped: 0, activityIds: [401] } }])
      render()
      await screen.findByText('ITEM_1001')
      fireEvent.mouseDown(screen.getByRole('gridcell', { name: '2행 Fix' }), { button: 0 })
      fireEvent.mouseDown(screen.getByRole('gridcell', { name: '3행 Fix' }), { button: 0, shiftKey: true })
      fireEvent.contextMenu(screen.getByRole('gridcell', { name: '3행 Fix' }))
      await userEvent.click(screen.getByRole('menuitem', { name: 'GPT로 다듬기 (2행)' }))
      expect(await screen.findByRole('heading', { name: 'GPT로 다듬기 (2행)' })).toBeInTheDocument()
      expect(await screen.findByText('다듬기 기본 지시')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: '다듬기' }))
      expect(await screen.findByText('카오스 성물')).toBeInTheDocument()
      expect(calls.find((c) => c.url.includes('/segments/polish'))!.body).toEqual({ segmentIds: [10, 11] })
      const before = calls.filter((c) => c.url.includes('/review/1/rows?sheet=UI')).length
      await userEvent.click(screen.getByRole('button', { name: '넣기 (1행)' }))
      expect(await screen.findByText('다듬기 1행 넣음')).toBeInTheDocument()
      expect(calls.find((c) => c.url.includes('/segments/save-batch'))!.body).toEqual({ items: [{ segmentId: 11, text: '카오스 성물', base: '혼돈의 성물' }] })
      expect(screen.queryByRole('heading', { name: 'GPT로 다듬기 (2행)' })).toBeNull()
      await waitFor(() => expect(calls.filter((c) => c.url.includes('/review/1/rows?sheet=UI')).length).toBeGreaterThan(before))
      fireEvent.keyDown(screen.getByRole('grid'), { key: 'z', code: 'KeyZ', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(1))
      expect(undoCalls(calls)[0].body).toEqual({ activityIds: [400] })
      expect(await screen.findByText('GPT로 다듬기 1행 되돌림 · 1행')).toBeInTheDocument()
    })

    it('paste pushes one undo unit', async () => {
      const { calls } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/10/translation', body: { ...saved, row: { ...saved.row, id: 10, currentText: '가' }, activityId: 1 } },
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, row: { ...saved.row, currentText: '나' }, activityId: 2 } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 2, skipped: 0, activityIds: [5, 6] } }])
      render()
      await screen.findByText('ITEM_1001')
      const grid = screen.getByRole('grid')
      fireEvent.keyDown(grid, { key: 'ArrowDown' })   // 첫 키 = 1행 Fix
      fireEvent.paste(grid, { clipboardData: { getData: () => '가\n나' } })
      await waitFor(() => expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(2))
      await screen.findByText('저장됨')
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      await waitFor(() => expect(undoCalls(calls)).toHaveLength(1))
      expect([...(undoCalls(calls)[0].body as { activityIds: number[] }).activityIds].sort()).toEqual([1, 2])
      expect(await screen.findByText('붙여넣기 2행 되돌림 · 2행')).toBeInTheDocument()
      fireEvent.keyDown(grid, { key: 'z', code: 'KeyZ', ctrlKey: true })
      expect(await screen.findByText('되돌릴 것이 없습니다')).toBeInTheDocument()
      expect(undoCalls(calls)).toHaveLength(1)
    })

    it('paste saves rows one at a time', async () => {
      // 동시에 저장하면 같은 원문 자동 반영·번역 메모리가 서로 잠가 DB 교착이 난다(2026-11-13) — 앞 행이 끝나야 다음 행을 보낸다
      const { fn, calls } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/10/translation', body: { ...saved, row: { ...saved.row, id: 10, currentText: '가' }, activityId: 1 } },
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, row: { ...saved.row, currentText: '나' }, activityId: 2 } }])
      const real = fn.getMockImplementation()!
      let release!: () => void
      const gate = new Promise<void>((r) => { release = r })
      let held = false
      fn.mockImplementation(async (input, init) => {
        if (!held && (init?.method ?? 'GET').toUpperCase() === 'PUT') { held = true; const res = real(input, init); await gate; return res }
        return real(input, init)
      })
      render()
      await screen.findByText('ITEM_1001')
      const grid = screen.getByRole('grid')
      fireEvent.keyDown(grid, { key: 'ArrowDown' })
      fireEvent.paste(grid, { clipboardData: { getData: () => '가\n나' } })
      await waitFor(() => expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(1))
      await new Promise((r) => setTimeout(r, 30))
      expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(1)
      release()
      await waitFor(() => expect(calls.filter((c) => c.method === 'PUT')).toHaveLength(2))
    })

    it('undo stack resets when sheet changes', async () => {
      const { calls } = mockFetch([...routes(),
        { method: 'PUT', path: '/segments/11/translation', body: { ...saved, activityId: 77 } },
        { method: 'POST', path: '/activity/undo', body: { reverted: 1, skipped: 0, activityIds: [90] } }])
      render()
      await screen.findByText('ITEM_1001')
      await editFix(3, '성물')
      await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
      await screen.findByText('저장됨')
      await userEvent.click(screen.getByRole('tab', { name: /Skill/ }))
      await waitFor(() => expect(screen.queryByText('ITEM_1001')).not.toBeInTheDocument())
      fireEvent.keyDown(screen.getByRole('grid'), { key: 'z', code: 'KeyZ', ctrlKey: true })
      expect(await screen.findByText('되돌릴 것이 없습니다')).toBeInTheDocument()
      expect(undoCalls(calls)).toHaveLength(0)
    })

    it('copy all fix fetches every page', async () => {
      const writeText = vi.fn(() => Promise.resolve())
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
      const { calls } = mockFetch([
        { path: /\/review\/1\/rows\?sheet=UI&page=1&size=500/, body: { rows: [{ ...rows[0], id: 12, fix: '셋\t넷' }], page: 1, size: 500, total: 501 } },
        { path: /\/review\/1\/rows\?sheet=UI&page=0&size=500/, body: { rows, page: 0, size: 500, total: 501 } },
        ...routes()])
      render()
      await screen.findByText('ITEM_1001')
      fireEvent.contextMenu(screen.getByRole('columnheader', { name: '수정 한국어 (Fix)' }))
      fireEvent.click(screen.getByRole('menuitem', { name: 'Fix 열 전체 복사' }))
      expect(await screen.findByText('Fix 3행 복사')).toBeInTheDocument()
      expect(writeText).toHaveBeenCalledWith('확인\n혼돈의 성물\n"셋\t넷"')
      expect(calls.some((c) => c.url.includes('sheet=UI&page=1&size=500'))).toBe(true)
    })
  })
})
