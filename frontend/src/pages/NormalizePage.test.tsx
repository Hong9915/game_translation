import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { NormalizePage } from './NormalizePage'

const project = { id: 7, fileName: 'Skill_DB1.xlsx', gameId: 3, gameName: '삼국지M', sheetCount: 2, rowCount: 20, uploadedAt: '2026-09-24T00:00:00Z', currentStep: 1 }
const headers = [
  { letter: 'A', text: 'SkillID' }, { letter: 'B', text: 'Desc_CN' },
  { letter: 'C', text: 'Desc_EN' }, { letter: 'D', text: 'Desc_KR' },
]
const preview = [['SKILL_1', '攻击力提升15%', 'ATK +15%', '공격력 증가 15%']]
const sheets = {
  sheets: [
    { name: 'Skill', rowCount: 20, headerRow: 1, headers, preview, guess: { src: 'B', en: 'C', key: 'A', pairs: [{ src: 'B', en: 'C', ko: 'D' }] } },
    { name: 'Quest', rowCount: 9, headerRow: 1, headers, preview, guess: { src: 'B', en: 'C', key: 'A', pairs: [{ src: 'B', en: 'C', ko: null }] } },
  ],
}
const emptyCurrent = { configs: [], stats: { totalRows: 0, normalized: 0, skipped: 0, withEn: 0, kept: 0, intentional: 0 }, preview: [], page: 0, size: 50, total: 0 }
const normalized = {
  configs: [{ config: { sheetName: 'Skill', headerRow: 1, keyCol: 'A', pairs: [{ srcCol: 'B', enCol: 'C', koColMode: 'EXISTING_TO_NEW', koCol: 'D', koHeader: 'KR_Fix' }], retranslateExisting: false }, stats: { totalRows: 20, normalized: 18, skipped: 2, withEn: 18, kept: 17, intentional: 1 } }],
  stats: { totalRows: 20, normalized: 18, skipped: 2, withEn: 18, kept: 17, intentional: 1 },
  preview: [], page: 0, size: 50, total: 18,
}
const render = () => renderWithProviders(<NormalizePage />, { route: '/normalize/7', path: '/normalize/:id' })
// 주의(브리프 그대로 두면 실패): mockFetch는 routes.find()로 먼저 나온 항목을 우선한다. '/projects/7'은
// '/projects/7/sheets'·'/projects/7/normalize' URL의 부분 문자열이기도 해서, 제일 앞에 두면 그 두 요청까지
// 가로채 버린다. 그래서 구체적인 경로를 먼저 두고 '/projects/7'은 맨 뒤(무엇에도 안 걸린 요청을 받는 자리)로 둔다.
const routes = (current: unknown = emptyCurrent) => [
  { path: '/sheets', body: sheets },
  { method: 'GET', path: /\/projects\/7\/normalize/, body: current },
  { method: 'PUT', path: '/projects/7/normalize', body: normalized },
  { path: '/projects/7', body: project },
]

describe('NormalizePage v2', () => {
  it('preselects the guessed roles and the new fix column', async () => {
    mockFetch(routes())
    render()
    expect(await screen.findByRole('combobox', { name: 'B열 역할' })).toHaveValue('SRC:0')
    expect(screen.getByRole('combobox', { name: 'A열 역할' })).toHaveValue('ID')
    expect(screen.getByRole('combobox', { name: 'D열 역할' })).toHaveValue('KO:0')
    expect(screen.getByRole('radio', { name: /새 열로 추가/ })).toBeChecked()
    expect(screen.getByText('KR_Fix')).toBeInTheDocument()
  })

  it('sends the roles as column pairs when normalizing', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('button', { name: '이 시트 정규화' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({
      sheetName: 'Skill', headerRow: 1, keyCol: 'A',
      pairs: [{ srcCol: 'B', enCol: 'C', koColMode: 'EXISTING_TO_NEW', koCol: 'D', koHeader: 'KR_Fix' }],
    })
  })

  it('switches the pair mode when the fix column is set to overwrite', async () => {
    const { calls } = mockFetch(routes())
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('radio', { name: /기존 한국어 열에 덮어쓰기/ }))
    await userEvent.click(screen.getByRole('button', { name: '이 시트 정규화' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    // calls[].body는 unknown이라 곧바로 .pairs[0]를 인덱싱하면 tsc가 막는다(NonNullable<unknown>은 {}).
    // 그래서 필요한 모양만 딱 잘라 캐스팅한다 — 검사하는 값은 브리프 원안과 같다.
    const put = calls.find((c) => c.method === 'PUT')?.body as { pairs: unknown[] } | undefined
    expect(put?.pairs[0]).toMatchObject({ koColMode: 'EXISTING', koCol: 'D' })
  })

  it('disables overwrite when no column is marked as existing korean', async () => {
    mockFetch(routes())
    render()
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'D열 역할' }), 'NONE')
    expect(screen.getByRole('radio', { name: /기존 한국어 열에 덮어쓰기/ })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /새 열로 추가/ })).toBeChecked()
  })

  it('refuses to normalize without a source column', async () => {
    mockFetch(routes())
    render()
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'B열 역할' }), 'NONE')
    expect(screen.getByRole('button', { name: '이 시트 정규화' })).toBeDisabled()
  })

  it('goes to the review workspace after normalizing', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('button', { name: '이 시트 정규화' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/review/7'))
  })

  // 브리프 원안은 이 시나리오를 한 it 안에서 render()→rerender()로 두 프로젝트 상태를 오가며 확인했는데,
  // rerender가 라우트/모의 서버를 갈아끼우는 용도로 쓰여 헷갈린다. 두 개의 별도 it으로 나눠 같은 두 사실
  // (정규화 전엔 없음 / 정규화된 설정이 하나라도 있으면 있음)을 각각 단순하게 확인한다.
  it('hides the 검수로 link before any sheet is normalized', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    expect(screen.queryByRole('button', { name: '검수로' })).not.toBeInTheDocument()
  })

  it('shows the 검수로 link once a sheet is normalized', async () => {
    mockFetch(routes(normalized))
    render()
    expect(await screen.findByRole('button', { name: '검수로' })).toBeInTheDocument()
  })

  // 리뷰 지적: 저장된 설정 복원이 sheets/current 두 쿼리의 응답 순서에 경합한다. sheets가 먼저 오면
  // 추측값으로 채우는 보정이 먼저 실행되고, 그 뒤 늦게 도착한 current(저장된 설정)는 조건이 이미 거짓이라
  // 반영되지 않는다. current를 sheets보다 늦게 응답하도록 만들어 경합을 고정한다.
  it('restores the saved config once the slower current-config fetch settles (not the guess)', async () => {
    // 추측값과 다르다는 걸 보여주려고 keyCol을 비워 저장했다고 가정한다(추측은 keyCol='A')
    const savedDiffersFromGuess = {
      configs: [{
        config: { sheetName: 'Skill', headerRow: 1, keyCol: null, pairs: [{ srcCol: 'B', enCol: 'C', koColMode: 'EXISTING_TO_NEW', koCol: 'D', koHeader: 'KR_Fix' }], retranslateExisting: false },
        stats: normalized.configs[0].stats,
      }],
      stats: normalized.stats, preview: [], page: 0, size: 50, total: 18,
    }
    let releaseCurrent: () => void = () => {}
    const gate = new Promise<void>((resolve) => { releaseCurrent = resolve })
    const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()
      if (method === 'GET' && url.includes('/sheets')) {
        return new Response(JSON.stringify(sheets), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (method === 'GET' && /\/projects\/7\/normalize/.test(url)) {
        await gate // sheets보다 늦게 응답하도록 붙잡아 둔다
        return new Response(JSON.stringify(savedDiffersFromGuess), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.includes('/projects/7')) {
        return new Response(JSON.stringify(project), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({ message: 'no mock' }), { status: 404 })
    })
    vi.stubGlobal('fetch', fn)

    render()
    // sheets가 실제로 로드·렌더까지 끝났다는 걸 sheets 데이터에만 기대는 다른 표시(+ 시트 추가 버튼)로
    // 확인한다 — fetch 호출만 확인하면 응답이 실제로 처리되기 전에 통과해 버려 경합을 못 잡는다.
    await screen.findByRole('button', { name: '+ 시트 추가' })
    // sheets는 이미 왔지만 current는 아직이라, 추측값으로도 화면이 채워지면 안 된다(정착까지 기다려야 한다)
    expect(screen.queryByRole('combobox', { name: 'B열 역할' })).not.toBeInTheDocument()

    releaseCurrent()
    // 저장된 설정이 도착하면 그제서야 채워지는데, 추측값(keyCol='A')이 아니라 저장된 값(keyCol 없음)이어야 한다
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'A열 역할' })).toHaveValue('NONE'))
    expect(screen.getByRole('combobox', { name: 'D열 역할' })).toHaveValue('KO:0')
    expect(screen.getByRole('radio', { name: /새 열로 추가/ })).toBeChecked()
  })

  // 최종 리뷰 Finding 1: 이미 정규화한 시트를 다시 정규화하면 번역·이력·담당·완료가 경고 없이 사라진다.
  // 옛 화면의 confirmingRun 인라인 확인을 v2에도 되살린다.
  it('warns inline instead of sending PUT when re-normalizing a sheet that already has rows', async () => {
    const { calls } = mockFetch(routes(normalized))
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('button', { name: '이 시트 정규화' }))
    expect(await screen.findByText(/다시 정규화하면.*지워집니다/)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })

  it('sends the PUT once re-normalizing is confirmed', async () => {
    const { calls } = mockFetch(routes(normalized))
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('button', { name: '이 시트 정규화' }))
    await screen.findByText(/다시 정규화하면.*지워집니다/)
    await userEvent.click(screen.getByRole('button', { name: '다시 정규화' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
  })

  it('cancels the re-normalize warning without sending a PUT, and re-clicking asks again', async () => {
    const { calls } = mockFetch(routes(normalized))
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('button', { name: '이 시트 정규화' }))
    await screen.findByText(/다시 정규화하면.*지워집니다/)
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByText(/다시 정규화하면.*지워집니다/)).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
    expect(screen.getByRole('button', { name: '이 시트 정규화' })).toBeInTheDocument()
  })

  // 리뷰가 남긴 Minor: 덮어쓰기 모드에서 그 대상 열을 지우면 라디오가 새 열로 추가로 되돌아가야 한다.
  it('reverts the fix mode to 새 열로 추가 once the overwritten column is cleared', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('combobox', { name: 'B열 역할' })
    await userEvent.click(screen.getByRole('radio', { name: /기존 한국어 열에 덮어쓰기/ }))
    expect(screen.getByRole('radio', { name: /기존 한국어 열에 덮어쓰기/ })).toBeChecked()

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'D열 역할' }), 'NONE')

    expect(screen.getByRole('radio', { name: /새 열로 추가/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /기존 한국어 열에 덮어쓰기/ })).toBeDisabled()
  })
})
