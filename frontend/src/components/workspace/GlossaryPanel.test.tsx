import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import GlossaryPanel from './GlossaryPanel'
import { api } from '../../api/client'
import { mockFetch } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'

const entry = {
  id: 1, gameId: 7, kind: 'KO', matchMode: 'PARTIAL',
  find: '심판', ko: '판결', note: null, oldKo: null, shared: false, updatedAt: '2026-09-24T00:00:00Z',
}

describe('GlossaryPanel', () => {
  it('이번 게임 탭과 공유 탭을 보여 준다', async () => {
    globalThis.fetch = mockFetch([{ path: '/games/7/glossary', body: [entry] }]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    expect(await screen.findByRole('tab', { name: '이번 게임' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '공유 단어집' })).toBeInTheDocument()
    expect(await screen.findByText('심판')).toBeInTheDocument()
  })

  it('입력하면 대상 건수를 미리 보여 준다', async () => {
    globalThis.fetch = mockFetch([
      { path: '/games/7/glossary/apply/count', body: { count: 12 } },
      { path: '/games/7/glossary', body: [] },
    ]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '심판')
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '판결')
    expect(await screen.findByText('대상 12행')).toBeInTheDocument()
  })

  it('저장하면 반영된 행 수를 알린다', async () => {
    globalThis.fetch = mockFetch([
      { path: '/games/7/glossary/apply/count', body: { count: 12 } },
      { method: 'POST', path: '/games/7/glossary', body: { entry, applied: 12 } },
      { path: '/games/7/glossary', body: [] },
    ]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '심판')
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '판결')
    await screen.findByText('대상 12행')
    await userEvent.click(screen.getByRole('button', { name: /추가/ }))
    expect(await screen.findByText('12행 반영됨')).toBeInTheDocument()
  })

  it('SRC 항목을 저장하면 불일치 다시 계산 안내를 같이 보여 주고, KO 항목은 보여 주지 않는다', async () => {
    globalThis.fetch = mockFetch([
      { path: '/games/7/glossary/apply/count', body: { count: 0 } },
      { method: 'POST', path: '/games/7/glossary', body: { entry, applied: 0 } },
      { path: '/games/7/glossary', body: [] },
    ]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    const hint = '이미 있는 행의 용어 불일치 표시는 바로 바뀌지 않습니다. 새로 번역하거나 저장한 행부터 반영됩니다.'

    // 종류 기본값은 SRC — 저장해도 0행 반영됨이 나오는 이유와 갱신 방법을 알려 준다
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '正义')
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '정의')
    await userEvent.click(screen.getByRole('button', { name: /추가/ }))
    expect(await screen.findByText('0행 반영됨')).toBeInTheDocument()
    expect(screen.getByText(hint)).toBeInTheDocument()

    // KO 항목은 저장이 곧 반영이라 안내가 필요 없다
    await userEvent.selectOptions(screen.getByLabelText('종류'), 'KO')
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '심판')
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '판결')
    await userEvent.click(screen.getByRole('button', { name: /추가/ }))
    await waitFor(() => expect(screen.queryByText(hint)).not.toBeInTheDocument())
    expect(screen.getByText('0행 반영됨')).toBeInTheDocument()
  })

  it('공유로 올리기를 누르면 공유 API를 부른다', async () => {
    const { fn, calls } = mockFetch([
      { path: '/games/7/glossary', body: [entry] },
      { method: 'POST', path: '/glossary/1/share', body: { ...entry, gameId: undefined, shared: true } },
    ])
    globalThis.fetch = fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: '공유로 올리기' }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/glossary/1/share'))).toBe(true))
  })

  it('KO+들어 있으면인데 찾을 문자열이 한 글자면 건수를 부르지 않고 경고를 보여 준다', async () => {
    const { fn, calls } = mockFetch([{ path: '/games/7/glossary', body: [] }])
    globalThis.fetch = fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.selectOptions(screen.getByLabelText('종류'), 'KO')
    await userEvent.selectOptions(screen.getByLabelText('맞추기'), 'PARTIAL')
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '가')
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '나')
    expect(await screen.findByText('한 글자는 너무 많은 문장에 걸립니다. 두 글자 이상을 권합니다.')).toBeInTheDocument()
    // 디바운스 창(300ms)이 지나도록 기다린 뒤에도 count 호출이 없어야 한다
    await new Promise((r) => setTimeout(r, 400))
    expect(calls.some((c) => c.url.includes('/apply/count'))).toBe(false)
  })

  it('수정을 누르면 폼이 채워지고 저장 버튼으로 바뀌며, 저장하면 PUT을 부른다', async () => {
    const { fn, calls } = mockFetch([
      { path: '/games/7/glossary', body: [entry] },
      { method: 'PUT', path: '/glossary/1', body: { entry: { ...entry, ko: '선고' }, applied: 3 } },
    ])
    globalThis.fetch = fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: '수정' }))
    expect(screen.getByLabelText('찾을 문자열')).toHaveValue('심판')
    expect(screen.getByLabelText('쓸 한국어')).toHaveValue('판결')
    expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('쓸 한국어'))
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '선고')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url.endsWith('/glossary/1'))).toBe(true))
    expect(await screen.findByText('3행 반영됨')).toBeInTheDocument()
  })

  it('삭제는 줄 안에서 한 번 더 확인한 뒤 DELETE를 부른다 — 브라우저 confirm()은 쓰지 않는다', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm')
    const { fn, calls } = mockFetch([
      { path: '/games/7/glossary', body: [entry] },
      { method: 'DELETE', path: '/glossary/1', status: 204 },
    ])
    globalThis.fetch = fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: `${entry.find} 삭제` }))
    // 아직 지우지 않았다 — 취소하면 원래 버튼으로 돌아온다
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('button', { name: '정말 삭제' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: `${entry.find} 삭제` }))
    await userEvent.click(screen.getByRole('button', { name: '정말 삭제' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE' && c.url.endsWith('/glossary/1'))).toBe(true))
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('건수 응답이 뒤섞여 도착해도 최신 입력의 건수만 보여 준다 (경쟁 상태)', async () => {
    globalThis.fetch = mockFetch([{ path: '/games/7/glossary', body: [] }]).fn
    let resolveFirst!: (v: { count: number }) => void
    let resolveSecond!: (v: { count: number }) => void
    const first = new Promise<{ count: number }>((res) => { resolveFirst = res })
    const second = new Promise<{ count: number }>((res) => { resolveSecond = res })
    const countSpy = vi.spyOn(api.glossary, 'count').mockReturnValueOnce(first).mockReturnValueOnce(second)
    try {
      renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
      await userEvent.type(screen.getByLabelText('찾을 문자열'), '가나')
      await userEvent.type(screen.getByLabelText('쓸 한국어'), '다라')
      await waitFor(() => expect(countSpy).toHaveBeenCalledTimes(1))
      // 두 번째 입력(디바운스가 다시 걸려 두 번째 count 요청을 만든다)
      await userEvent.type(screen.getByLabelText('찾을 문자열'), '다')
      await waitFor(() => expect(countSpy).toHaveBeenCalledTimes(2))

      // 나중에 보낸(최신 입력의) 요청이 먼저 돌아온다 — 이 건수(7)를 보여줘야 한다.
      resolveSecond({ count: 7 })
      expect(await screen.findByText('대상 7행')).toBeInTheDocument()

      // 먼저 보낸(옛 입력의) 요청이 뒤늦게 돌아온다 — 이미 지나간 입력이니 무시해야 한다.
      resolveFirst({ count: 5 })
      await new Promise((r) => setTimeout(r, 0))
      expect(screen.queryByText('대상 5행')).not.toBeInTheDocument()
      expect(screen.getByText('대상 7행')).toBeInTheDocument()
    } finally {
      countSpy.mockRestore()
    }
  })

  it('전체 다시 적용을 누르면 적용 결과를 4초 보여 주고 사라진다', async () => {
    const { fn, calls } = mockFetch([
      { method: 'POST', path: '/games/7/glossary/apply', body: { applied: 5 } },
      { path: '/games/7/glossary', body: [] },
    ])
    globalThis.fetch = fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: '전체 다시 적용' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/games/7/glossary/apply'))).toBe(true))
    expect(await screen.findByText('5행 반영됨')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('5행 반영됨')).not.toBeInTheDocument(), { timeout: 4500 })
  }, 6000)

  it('initial이 같은 seq로 새 오브젝트가 와도 입력 중인 값을 덮어쓰지 않고, seq가 바뀌면 다시 채운다', async () => {
    globalThis.fetch = mockFetch([{ path: '/games/7/glossary', body: [] }]).fn
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrap = (initial: { kind: 'SRC' | 'KO'; find: string; matchMode: 'WHOLE' | 'PARTIAL'; seq: number }) => (
      <QueryClientProvider client={client}>
        <GlossaryPanel gameId={7} projectId={1} onClose={() => {}} initial={initial} />
      </QueryClientProvider>
    )
    const { rerender } = render(wrap({ kind: 'SRC', find: '가', matchMode: 'WHOLE', seq: 1 }))
    expect(await screen.findByLabelText('찾을 문자열')).toHaveValue('가')
    await userEvent.type(screen.getByLabelText('쓸 한국어'), '나')

    // 같은 seq — 새 오브젝트 참조가 와도 입력 중인 값을 덮어쓰면 안 된다
    rerender(wrap({ kind: 'SRC', find: '가', matchMode: 'WHOLE', seq: 1 }))
    expect(screen.getByLabelText('쓸 한국어')).toHaveValue('나')

    // seq가 바뀌면 종류·맞추기·찾을 문자열을 다시 채우고 쓸 한국어는 비운다
    rerender(wrap({ kind: 'KO', find: '다', matchMode: 'PARTIAL', seq: 2 }))
    expect(screen.getByLabelText('종류')).toHaveValue('KO')
    expect(screen.getByLabelText('맞추기')).toHaveValue('PARTIAL')
    expect(screen.getByLabelText('찾을 문자열')).toHaveValue('다')
    expect(screen.getByLabelText('쓸 한국어')).toHaveValue('')
  })

  const srcPartialEntry = { ...entry, id: 2, kind: 'SRC', matchMode: 'PARTIAL', find: '升星', ko: '성급', oldKo: '승급' }
  const suggestion = {
    rows: 41,
    candidates: [
      { oldKo: '승급', rows: 38, example: { src: '升星计划', before: '승급 계획', after: '성급 계획' } },
      { oldKo: '별 승급', rows: 2, example: { src: '升星英雄', before: '별 승급 영웅', after: '성급 영웅' } },
    ],
  }
  /** SRC + 들어 있으면 조건으로 폼을 맞추고 찾을 말·쓸 한국어를 넣는다. */
  async function typeSrcPartial(find = '升星', ko = '성급') {
    await userEvent.selectOptions(screen.getByLabelText('맞추기'), 'PARTIAL')
    await userEvent.type(screen.getByLabelText('찾을 문자열'), find)
    if (ko) await userEvent.type(screen.getByLabelText('쓸 한국어'), ko)
  }

  it('SRC 부분 항목이면 기존 표기 후보를 보여 주고 첫 후보를 고른 채 건수를 센다', async () => {
    // mockFetch는 url.includes로 첫 일치 route를 고른다 — 더 구체적인 경로를 앞에 둔다
    const m = mockFetch([
      { path: '/games/7/glossary/suggest', body: suggestion },
      { path: '/games/7/glossary/apply/count', body: { count: 38 } },
      { path: '/games/7/glossary', body: [] },
    ])
    globalThis.fetch = m.fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await typeSrcPartial()
    expect(await screen.findByText('승급 계획 → 성급 계획')).toBeInTheDocument()
    expect(screen.getByLabelText('기존 표기 승급')).toBeChecked()
    expect(screen.getByText('38행')).toBeInTheDocument()
    expect(await screen.findByText('대상 38행')).toBeInTheDocument()
    const countCall = m.calls.find((c) => c.url.includes('/apply/count'))
    expect(countCall?.url).toContain('oldKo=%EC%8A%B9%EA%B8%89')
  })

  it('저장 요청에 고른 기존 표기를 싣는다', async () => {
    const m = mockFetch([
      { path: '/games/7/glossary/suggest', body: suggestion },
      { path: '/games/7/glossary/apply/count', body: { count: 38 } },
      { method: 'POST', path: '/games/7/glossary', body: { entry: srcPartialEntry, applied: 38 } },
      { path: '/games/7/glossary', body: [] },
    ])
    globalThis.fetch = m.fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await typeSrcPartial()
    await screen.findByLabelText('기존 표기 승급')
    await userEvent.click(screen.getByRole('button', { name: /추가/ }))
    expect(await screen.findByText('38행 반영됨')).toBeInTheDocument()
    const post = m.calls.find((c) => c.method === 'POST')
    expect(post?.body).toMatchObject({ kind: 'SRC', matchMode: 'PARTIAL', find: '升星', ko: '성급', oldKo: '승급' })
  })

  it('직접 입력과 없음으로 바꿀 수 있고, 없음이면 oldKo 없이 저장한다', async () => {
    const m = mockFetch([
      { path: '/games/7/glossary/suggest', body: suggestion },
      { path: '/games/7/glossary/apply/count', body: { count: 5 } },
      { method: 'POST', path: '/games/7/glossary', body: { entry: { ...srcPartialEntry, oldKo: null }, applied: 0 } },
      { path: '/games/7/glossary', body: [] },
    ])
    globalThis.fetch = m.fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await typeSrcPartial()
    await screen.findByLabelText('기존 표기 승급')

    await userEvent.click(screen.getByLabelText('기존 표기 직접 입력'))
    await userEvent.type(screen.getByLabelText('기존 표기'), '성장')
    await waitFor(() => expect(m.calls.some((c) => c.url.includes('oldKo=%EC%84%B1%EC%9E%A5'))).toBe(true))

    await userEvent.click(screen.getByLabelText('기존 표기 없음'))
    expect(screen.getByText('자동 치환은 안 됩니다. 용어 불일치 표시와 번역 지시로만 쓰입니다.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /추가/ }))
    await screen.findByText('0행 반영됨')
    const post = m.calls.find((c) => c.method === 'POST')
    expect(post?.body).toMatchObject({ oldKo: null })
  })

  it('후보 조회가 실패하면 직접 입력과 없음만 남는다', async () => {
    // Review Focus 5: 후보가 안 와도 저장은 막히지 않는다
    globalThis.fetch = mockFetch([
      { path: '/games/7/glossary/suggest', status: 500, body: { message: '통계 실패' } },
      { path: '/games/7/glossary/apply/count', body: { count: 0 } },
      { path: '/games/7/glossary', body: [] },
    ]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await typeSrcPartial()
    expect(await screen.findByRole('alert')).toHaveTextContent('통계 실패')
    expect(screen.getByLabelText('기존 표기 직접 입력')).toBeChecked()
    expect(screen.getByLabelText('기존 표기 없음')).toBeInTheDocument()
    expect(screen.queryByLabelText('기존 표기 승급')).not.toBeInTheDocument()
  })

  it('목록에 기존 표기를 보여 주고, 수정하면 저장된 값이 채워진다', async () => {
    globalThis.fetch = mockFetch([
      { path: '/games/7/glossary/suggest', body: { rows: 0, candidates: [] } },
      { path: '/games/7/glossary/apply/count', body: { count: 38 } },
      { path: '/games/7/glossary', body: [srcPartialEntry] },
    ]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    expect(await screen.findByText('(기존: 승급)')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '수정' }))
    expect(screen.getByLabelText('기존 표기 직접 입력')).toBeChecked()
    expect(screen.getByLabelText('기존 표기')).toHaveValue('승급')
  })

  it('SRC 통째 항목이나 KO 항목에는 기존 표기 선택이 없다', async () => {
    globalThis.fetch = mockFetch([
      { path: '/games/7/glossary/apply/count', body: { count: 1 } },
      { path: '/games/7/glossary', body: [] },
    ]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '正义裁决')
    expect(screen.queryByLabelText('기존 표기 없음')).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('종류'), 'KO')
    await userEvent.selectOptions(screen.getByLabelText('맞추기'), 'PARTIAL')
    expect(screen.queryByLabelText('기존 표기 없음')).not.toBeInTheDocument()
  })

  it('기존 표기가 없는 항목을 수정하면 없음이 선택된 채 열린다 — 비고만 고쳐 저장해도 행을 바꾸지 않는다', async () => {
    // 리뷰 Important 3: 감시 전용으로 저장한 항목(또는 old_ko가 없는 옛 항목)을 열었을 때 첫 후보가 미리 선택되면
    // 비고 오타를 고치고 저장하는 순간 수백 행이 바뀐다.
    const m = mockFetch([
      { path: '/games/7/glossary/suggest', body: suggestion },
      { path: '/games/7/glossary/apply/count', body: { count: 38 } },
      { method: 'PUT', path: '/glossary/2', body: { entry: { ...srcPartialEntry, oldKo: null }, applied: 0 } },
      { path: '/games/7/glossary', body: [{ ...srcPartialEntry, oldKo: null }] },
    ])
    globalThis.fetch = m.fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await screen.findByText('升星')
    await userEvent.click(screen.getByRole('button', { name: '수정' }))
    expect(await screen.findByLabelText('기존 표기 승급')).toBeInTheDocument()   // 후보는 보여 주되
    expect(screen.getByLabelText('기존 표기 없음')).toBeChecked()               // 고르기 전에는 없음
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    await screen.findByText('0행 반영됨')
    const put = m.calls.find((c) => c.method === 'PUT')
    expect(put?.body).toMatchObject({ oldKo: null })
  })

  it('찾을 말을 바꾸면 이전 후보 선택이 풀린다 — 새 후보가 오기 전에 저장해도 옛 표기가 실리지 않는다', async () => {
    // 리뷰 Important 4: 升星의 후보 "승급"이 선택된 채 찾을 말을 고치면, 디바운스·응답 대기 동안 저장 시 옛 표기가 새 용어에 적용된다.
    const m = mockFetch([
      { path: '/games/7/glossary/suggest', body: suggestion },
      { path: '/games/7/glossary/apply/count', body: { count: 38 } },
      { method: 'POST', path: '/games/7/glossary', body: { entry: { ...srcPartialEntry, oldKo: null }, applied: 0 } },
      { path: '/games/7/glossary', body: [] },
    ])
    globalThis.fetch = m.fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    await typeSrcPartial()
    await screen.findByLabelText('기존 표기 승급')
    await userEvent.type(screen.getByLabelText('찾을 문자열'), '英')   // 升星英 — 응답 전
    expect(screen.getByLabelText('기존 표기 승급')).not.toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: /추가/ }))
    await screen.findByText('0행 반영됨')
    const post = m.calls.find((c) => c.method === 'POST')
    expect(post?.body).toMatchObject({ find: '升星英', oldKo: null })
  })

  it('추천 보기는 이 게임의 추천 창을 새 탭으로 연다', async () => {
    globalThis.fetch = mockFetch([{ path: '/games/7/glossary', body: [] }]).fn
    renderWithProviders(<GlossaryPanel gameId={7} projectId={1} onClose={() => {}} />)
    const link = screen.getByRole('link', { name: '추천 보기' })
    expect(link).toHaveAttribute('href', '/games/7/glossary/recommend')
    expect(link).toHaveAttribute('target', '_blank')
  })
})
