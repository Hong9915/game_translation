import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { GamePage } from './GamePage'

const game = { id: 3, name: '드래곤 레이드', projects: 2, memory: 2318, lastUploadedAt: '2026-09-15T09:00:00Z', createdAt: '2026-09-01T00:00:00Z' }
const projects = [
  { id: 1, fileName: 'ItemText_v3.xlsx', gameId: 3, gameName: '드래곤 레이드', sheetCount: 4, rowCount: 2318, uploadedAt: '2026-09-15T09:00:00Z', currentStep: 4 },
  { id: 2, fileName: 'skill_desc_cn.xlsx', gameId: 3, gameName: '드래곤 레이드', sheetCount: 1, rowCount: 864, uploadedAt: '2026-09-11T09:00:00Z', currentStep: 1 },
]
const uploaded = (id: number, fileName: string) => ({ id, fileName, gameId: 3, gameName: '드래곤 레이드', sheetCount: 1, rowCount: 10, uploadedAt: '2026-09-16T00:00:00Z', currentStep: 1 })
const xlsx = (name: string) => new File(['x'], name, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
const render = () => renderWithProviders(<GamePage />, { route: '/games/3', path: '/games/:gameId' })

describe('GamePage', () => {
  it('shows the game, lists its files and resumes at the current step', async () => {
    const { calls } = mockFetch([{ path: '/games/3', body: game }, { path: '/projects', body: projects }])
    render()
    expect(await screen.findByRole('heading', { name: '드래곤 레이드' })).toBeInTheDocument()
    expect(await screen.findByText('ItemText_v3.xlsx')).toBeInTheDocument()
    expect(screen.getByText(/2,318행/)).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/projects?gameId=3'))).toBe(true)
    await userEvent.click(screen.getByRole('link', { name: /^ItemText_v3\.xlsx(?!.*원본)/ }))
    expect(screen.getByTestId('location')).toHaveTextContent('/review/1')
  })

  it('uploads one chosen file into this game and offers the next step', async () => {
    const { calls } = mockFetch([
      { path: '/games/3', body: game }, { path: '/projects', body: [] },
      { method: 'POST', path: '/projects', body: uploaded(9, 'new.xlsx') },
    ])
    render()
    expect(await screen.findByText('아직 올린 파일이 없습니다')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '정규화로' })).not.toBeInTheDocument()
    await userEvent.upload(screen.getByLabelText('파일 선택'), xlsx('new.xlsx'))
    const next = await screen.findByRole('button', { name: '정규화로' })
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ gameId: '3' })
    await userEvent.click(next)
    expect(screen.getByTestId('location')).toHaveTextContent('/normalize/9')
  })

  it('uploads a folder file by file, skips unsupported files and reports failures', async () => {
    let n = 0
    const { calls, fn } = mockFetch([{ path: '/games/3', body: game }, { path: '/projects', body: [] }])
    const base = fn.getMockImplementation()!
    fn.mockImplementation(async (input, init) => {
      if (init?.method === 'POST') {
        const fd = init.body as FormData
        const name = (fd.get('file') as File).name
        calls.push({ method: 'POST', url: String(input), body: Object.fromEntries(fd.entries()) })
        if (name === 'broken.xlsx') return new Response(JSON.stringify({ message: '엑셀 파일을 읽을 수 없습니다' }), { status: 400 })
        return new Response(JSON.stringify(uploaded(++n + 10, name)), { status: 201, headers: { 'Content-Type': 'application/json' } })
      }
      return base(input, init)
    })
    render()
    await screen.findByRole('heading', { name: '드래곤 레이드' })
    const files = [xlsx('a.xlsx'), xlsx('broken.xlsx'), new File(['x'], 'readme.txt'), new File(['x'], '~$a.xlsx'), xlsx('c.csv')]
    await userEvent.upload(screen.getByLabelText('폴더 선택'), files)
    await waitFor(() => expect(screen.getByText('올림 2개 · 실패 1개 · 건너뜀 2개')).toBeInTheDocument())
    expect(screen.getByText(/broken\.xlsx: 엑셀 파일을 읽을 수 없습니다/)).toBeInTheDocument()
    const posted = calls.filter((c) => c.method === 'POST').map((c) => (c.body as { file: File }).file.name)
    expect(posted).toEqual(['a.xlsx', 'broken.xlsx', 'c.csv'])
    // 여러 개 올리면 표에서 각각 진행한다
    expect(screen.queryByRole('button', { name: '정규화로' })).not.toBeInTheDocument()
  })

  it('renames the game inline', async () => {
    const { calls } = mockFetch([
      { path: '/games/3', body: game }, { path: '/projects', body: projects },
      { method: 'PATCH', path: '/games/3', body: { ...game, name: '드래곤 레이드 2' } },
    ])
    render()
    await userEvent.click(await screen.findByRole('button', { name: '이름 바꾸기' }))
    const input = screen.getByLabelText('게임 이름')
    await userEvent.clear(input)
    await userEvent.type(input, '드래곤 레이드 2{enter}')
    await waitFor(() => expect(calls.find((c) => c.method === 'PATCH')?.body).toEqual({ name: '드래곤 레이드 2' }))
    expect(await screen.findByRole('heading', { name: '드래곤 레이드 2' })).toBeInTheDocument()
  })

  it('deletes the game after inline confirmation and returns to the list', async () => {
    const { calls } = mockFetch([
      { path: '/games/3', body: game }, { path: '/projects', body: projects },
      { method: 'DELETE', path: '/games/3', status: 204 },
    ])
    render()
    await userEvent.click(await screen.findByRole('button', { name: '게임 삭제' }))
    expect(screen.getByText(/파일 2개와 번역 메모리가 모두 지워집니다/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('button', { name: '정말 삭제' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '게임 삭제' }))
    await userEvent.click(screen.getByRole('button', { name: '정말 삭제' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE' && c.url.endsWith('/games/3'))).toBe(true))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/))
  })

  it('deletes a file after inline confirmation', async () => {
    const { calls } = mockFetch([
      { path: '/games/3', body: game }, { path: '/projects', body: projects },
      { method: 'DELETE', path: '/projects/2', status: 204 },
    ])
    render()
    await screen.findByText('skill_desc_cn.xlsx')
    const askDelete = async () => {
      await userEvent.click(screen.getByRole('button', { name: 'skill_desc_cn.xlsx 더 보기' }))
      await userEvent.click(screen.getByRole('menuitem', { name: '삭제…' }))
    }
    await askDelete()
    const row = screen.getByRole('button', { name: '정말 삭제' }).closest('tr') as HTMLElement
    await userEvent.click(within(row).getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('button', { name: '정말 삭제' })).not.toBeInTheDocument()
    await askDelete()
    await userEvent.click(screen.getByRole('button', { name: '정말 삭제' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE' && c.url.endsWith('/projects/2'))).toBe(true))
  })

  it('offers the original download on each file row', async () => {
    mockFetch([{ path: '/games/3', body: game }, { path: '/projects', body: projects }])
    render()
    const link = await screen.findByRole('link', { name: 'ItemText_v3.xlsx 원본 내려받기' })
    expect(link).toHaveAttribute('href', expect.stringContaining('/projects/1/export/original'))
  })

  it('shows stage badges, confirmed progress and filters by stage', async () => {
    const reviewing = { ...projects[0], reviewRows: 2000, confirmedRows: 500 }
    const finished = { ...projects[0], id: 5, fileName: 'done.xlsx', reviewRows: 10, confirmedRows: 10 }
    mockFetch([{ path: '/games/3', body: game }, { path: '/projects', body: [reviewing, finished, projects[1]] }])
    render()
    const row = (await screen.findByText('ItemText_v3.xlsx')).closest('tr')!
    expect(within(row).getByText('검수 중')).toBeInTheDocument()
    expect(within(row).getByText('500 / 2,000')).toBeInTheDocument()
    expect(within(screen.getByText('skill_desc_cn.xlsx').closest('tr')!).getByText('정규화 전')).toBeInTheDocument()
    expect(within(screen.getByText('done.xlsx').closest('tr')!).getByText('확정 끝')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /정규화 전 1/ }))
    expect(screen.queryByText('ItemText_v3.xlsx')).not.toBeInTheDocument()
    expect(screen.getByText('skill_desc_cn.xlsx')).toBeInTheDocument()
  })

  it('sends a not-yet-normalized file to the normalize screen and a normalized one to the workspace', async () => {
    const fresh = { ...projects[0], id: 11, fileName: 'fresh.xlsx', currentStep: 1 }
    const done = { ...projects[0], id: 12, fileName: 'done.xlsx', currentStep: 3 }
    mockFetch([{ path: '/games/3', body: game }, { path: '/projects', body: [fresh, done] }])
    render()
    await userEvent.click(await screen.findByRole('link', { name: /^fresh\.xlsx(?!.*원본)/ }))
    expect(screen.getByTestId('location')).toHaveTextContent('/normalize/11')
  })

  it('최종 리뷰로 정규화한 파일에만 "최종 리뷰" 배지를 붙인다', async () => {
    mockFetch([{ path: '/games/3', body: game }, { path: '/projects', body: [{ ...projects[0], finalReview: true }, projects[1]] }])
    render()
    const finalRow = (await screen.findByText('ItemText_v3.xlsx')).closest('tr')!
    expect(within(finalRow).getByText('최종 리뷰')).toBeInTheDocument()
    expect(within(screen.getByText('skill_desc_cn.xlsx').closest('tr')!).queryByText('최종 리뷰')).toBeNull()
  })

  describe('단어장 요청', () => {
    const summary = { id: 3, fileName: '단어장_요청양식.xlsx', createdAt: '2026-10-30T09:00:00Z', total: 42, approved: 5, pending: 37 }
    // 구체적인 경로를 먼저 둔다(첫 일치가 이긴다).
    const base = [{ path: '/games/3/glossary/requests', body: [summary] }, { path: '/games/3', body: game }, { path: '/projects', body: [] }]

    it('단어장 요청 올리기로 양식을 올리면 요청 화면으로 간다', async () => {
      const { calls } = mockFetch([
        { method: 'POST', path: '/games/3/glossary/requests', body: { id: 3, gameId: 3, fileName: 'f.xlsx', createdAt: '2026-10-30T09:00:00Z', items: [] } },
        ...base,
      ])
      render()
      await screen.findByRole('heading', { name: '드래곤 레이드' })
      await userEvent.upload(screen.getByLabelText('단어장 요청 양식 선택'), xlsx('f.xlsx'))
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/games/3/glossary/requests/3'))
      expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ file: expect.any(File) })
    })

    it('지난 단어장 요청을 승인 수와 함께 보여 준다', async () => {
      mockFetch(base)
      render()
      expect(await screen.findByText(/승인 5\/42/)).toBeInTheDocument()
      // 09:00Z는 어느 시간대에서도 10월 30일이다 — 로컬 날짜 형식(ko-KR)으로 보인다
      expect(screen.getByText(/단어장_요청양식\.xlsx · 2026\. 10\. 30\. · 승인 5\/42/)).toBeInTheDocument()
    })

    it('양식이 아니면 서버 메시지를 보여 준다', async () => {
      mockFetch([
        { method: 'POST', path: '/games/3/glossary/requests', status: 400, body: { message: '단어장 요청양식이 아닙니다' } },
        ...base,
      ])
      render()
      await screen.findByRole('heading', { name: '드래곤 레이드' })
      await userEvent.upload(screen.getByLabelText('단어장 요청 양식 선택'), xlsx('bad.xlsx'))
      expect(await screen.findByText('단어장 요청양식이 아닙니다')).toBeInTheDocument()
    })
  })
})
