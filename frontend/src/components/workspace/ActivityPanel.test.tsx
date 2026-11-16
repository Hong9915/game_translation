import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import ActivityPanel from './ActivityPanel'
import { mockFetch } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'

const me = { id: 1, name: '형준', color: '#1f4b99' }
const now = new Date().toISOString()
const edit = {
  id: 30, kind: 'EDIT', reviewer: me, createdAt: now, rowCount: 3, sheet: 'UI',
  primary: { segmentId: 11, projectId: 1, sheet: 'UI', rowNo: 12, ordinal: 10, before: '혼돈의 성물', after: '혼돈 성물' },
  detail: { segmentId: 11 },
}
const firstEdit = { ...edit, id: 29, rowCount: 1, primary: { ...edit.primary, before: null, after: '처음 쓴 글' } }
const anonymous = { ...edit, id: 28, reviewer: null, rowCount: 1, primary: { ...edit.primary, before: null, after: '익명이 쓴 글' } }
const noPrimary = { ...edit, id: 27, rowCount: 2, primary: null, sheet: null }
const fixTerms = { id: 26, kind: 'FIX_TERMS', reviewer: me, createdAt: now, rowCount: 37, sheet: 'Skill', primary: null, detail: { sheet: 'Skill' } }
const emptyJob = { ...fixTerms, id: 25, kind: 'SHORTEN', rowCount: 0, sheet: null, detail: {} }
const glossary = { id: 24, kind: 'GLOSSARY_EDIT', reviewer: me, createdAt: now, rowCount: 0, sheet: null, primary: null, detail: { action: 'ADD', kind: 'KO', find: '심판', ko: '판결', oldKo: null } }

const render = (onJump = vi.fn()) => { renderWithProviders(<ActivityPanel projectId={1} onClose={() => {}} onJump={onJump} />); return onJump }

describe('ActivityPanel', () => {
  it('번역 수정은 누가·언제·어느 행·전후 본문을 보여 준다', async () => {
    mockFetch([{ path: '/projects/1/activity', body: { items: [edit] } }])
    render()
    expect(await screen.findByText('형준')).toBeInTheDocument()
    expect(screen.getByText('방금')).toBeInTheDocument()
    expect(screen.getByText('번역 수정')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'UI 12행' })).toBeInTheDocument()
    expect(screen.getByText('혼돈의 성물')).toHaveClass('line-through')
    expect(screen.getByText('혼돈 성물')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '같은 원문 2행 함께' })).toBeInTheDocument()
  })

  it('첫 판은 이전 본문 줄이 없고, 익명은 이름 없음, primary 없는 EDIT은 행 수만 보인다', async () => {
    mockFetch([{ path: '/projects/1/activity', body: { items: [firstEdit, anonymous, noPrimary] } }])
    render()
    await screen.findByText('처음 쓴 글')
    expect(document.querySelector('.line-through')).toBeNull()
    expect(screen.getByText('이름 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '같은 원문 2행' })).toBeInTheDocument()
  })

  it('작업류는 행 수를 보여 주고 펼치면 바뀐 행을 20개씩 읽는다', async () => {
    const { calls } = mockFetch([
      { path: '/projects/1/activity', body: { items: [fixTerms, emptyJob] } },
      { path: '/activity/26/rows', body: { rows: [{ segmentId: 5, projectId: 1, sheet: 'Skill', rowNo: 7, ordinal: 5, before: '화염', after: '불꽃' }], page: 0, size: 20, total: 37 } },
    ])
    const onJump = render()
    expect(await screen.findByText('용어 치환')).toBeInTheDocument()
    expect(screen.getByText('Skill · 37행')).toBeInTheDocument()
    expect(screen.getByText('바뀐 행 없음')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Skill · 37행' }))
    expect(await screen.findByRole('button', { name: 'Skill 7행' })).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/activity/26/rows?page=0&size=20'))).toBe(true)
    expect(screen.getByRole('button', { name: '더 보기 (36행 남음)' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Skill 7행' }))
    expect(onJump).toHaveBeenCalledWith('Skill', 5)
  })

  it('다른 파일의 행은 점프 버튼 없이 표시만 한다', async () => {
    mockFetch([
      { path: '/projects/1/activity', body: { items: [{ ...edit, primary: { ...edit.primary, projectId: 1 } }] } },
      { path: '/activity/30/rows', body: { rows: [
        { segmentId: 5, projectId: 1, sheet: 'UI', rowNo: 13, ordinal: 11, before: null, after: 'a' },
        { segmentId: 6, projectId: 9, sheet: 'Skill', rowNo: 4, ordinal: 2, before: null, after: 'b' },
      ], page: 0, size: 20, total: 2 } },
    ])
    const onJump = render()
    await userEvent.click(await screen.findByRole('button', { name: '같은 원문 2행 함께' }))
    expect(await screen.findByRole('button', { name: 'UI 13행' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Skill 4행/ })).toBeNull()
    expect(screen.getByText('다른 파일 · Skill 4행')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'UI 13행' }))
    expect(onJump).toHaveBeenCalledWith('UI', 11)
  })

  it('단어장 편집은 한 줄로', async () => {
    mockFetch([{ path: '/projects/1/activity', body: { items: [glossary] } }])
    render()
    expect(await screen.findByText('단어장 「심판 → 판결」 추가')).toBeInTheDocument()
    expect(screen.getByText('KO')).toBeInTheDocument()
  })

  it('행 링크를 누르면 시트와 순번으로 점프한다', async () => {
    mockFetch([{ path: '/projects/1/activity', body: { items: [edit] } }])
    const onJump = render()
    await userEvent.click(await screen.findByRole('button', { name: 'UI 12행' }))
    expect(onJump).toHaveBeenCalledWith('UI', 10)
  })

  it('nextBefore가 있으면 더 보기로 이어 읽는다', async () => {
    // mockFetch는 앞에서부터 찾으므로 before=24 경로를 일반 경로보다 앞에 둔다.
    const { calls } = mockFetch([
      { path: /\/projects\/1\/activity\?before=24/, body: { items: [{ ...edit, id: 3 }] } },
      { path: '/projects/1/activity', body: { items: [edit, glossary], nextBefore: 24 } },
    ])
    render()
    await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('activity?before=24'))).toBe(true))
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
  })

  it('되돌리기는 확인을 거쳐 POST하고 결과를 그 항목 아래 보여 준다', async () => {
    const { calls } = mockFetch([
      { method: 'POST', path: '/activity/30/revert', body: { reverted: 2, skipped: 1 } },
      { path: '/projects/1/activity', body: { items: [edit] } },
    ])
    render()
    await userEvent.click(await screen.findByRole('button', { name: '되돌리기' }))
    expect(screen.getByText('3행을 이전 본문으로 되돌립니다.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByText('3행을 이전 본문으로 되돌립니다.')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '되돌리기' }))
    await userEvent.click(screen.getByRole('button', { name: '예, 되돌리기' }))
    expect(await screen.findByText('2행 되돌림 · 1행 건너뜀')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.url.includes('/activity/30/revert'))).toBeTruthy()
  })

  it('조사 고침은 행 수와 되돌리기를 보여 준다', async () => {
    const josa = { ...edit, id: 31, kind: 'JOSA_FIX', rowCount: 5, primary: null, detail: {} }
    mockFetch([{ path: '/projects/1/activity', body: { items: [josa] } }])
    render()
    expect(await screen.findByText('조사 고침')).toBeInTheDocument()
    expect(screen.getByText(/5행/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeInTheDocument()
  })

  it('교정 규칙 적용은 라벨·행 수·되돌리기를 보여 준다', async () => {
    const rule = { ...edit, id: 32, kind: 'RULE_APPLY', rowCount: 4, primary: null, detail: { ruleId: 1, type: 'WORD', of: '엘리트', nf: '에픽' } }
    mockFetch([{ path: '/projects/1/activity', body: { items: [rule] } }])
    render()
    expect(await screen.findByText('교정 규칙 적용')).toBeInTheDocument()
    expect(screen.getByText(/4행/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeInTheDocument()
  })

  it('shows batch RETRANSLATE without primary row as expandable', async () => {
    const batch = { ...edit, id: 33, kind: 'RETRANSLATE', rowCount: 12, primary: null, sheet: null, detail: { engine: 'GPT' } }
    const { calls } = mockFetch([
      { path: '/projects/1/activity', body: { items: [batch] } },
      { path: '/activity/33/rows', body: { rows: [{ segmentId: 5, projectId: 1, sheet: 'UI', rowNo: 7, ordinal: 5, before: null, after: '새 번역' }], page: 0, size: 20, total: 12 } },
    ])
    render()
    expect(await screen.findByText('다시 번역')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /같은 원문/ })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '12행' }))
    expect(await screen.findByRole('button', { name: 'UI 7행' })).toBeInTheDocument()
    expect(calls.some((c) => c.url.includes('/activity/33/rows?page=0&size=20'))).toBe(true)
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeInTheDocument()
  })

  it('shows POLISH as a revertible job item', async () => {
    const polish = { ...edit, id: 32, kind: 'POLISH', rowCount: 4, primary: null, detail: {} }
    mockFetch([{ path: '/projects/1/activity', body: { items: [polish] } }])
    render()
    expect(await screen.findByText('GPT로 다듬기')).toBeInTheDocument()
    expect(screen.getByText(/4행/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeInTheDocument()
  })

  it('단어장 일괄 적용의 되돌리기 확인에는 항목이 남는다는 안내가 붙는다', async () => {
    mockFetch([{ path: '/projects/1/activity', body: { items: [fixTerms, { ...fixTerms, id: 23, kind: 'GLOSSARY_APPLY', sheet: null, detail: {}, rowCount: 5 }] } }])
    render()
    const buttons = await screen.findAllByRole('button', { name: '되돌리기' })
    expect(buttons).toHaveLength(2)
    await userEvent.click(buttons[1])
    expect(screen.getByText(/단어장 항목은 그대로 남아 다음 일괄 적용 때 다시 바뀝니다/)).toBeInTheDocument()
  })

  it('단어장 편집·빈 작업에는 되돌리기가 없고, 되돌림 항목은 되돌리기 라벨로 행을 펼친다', async () => {
    const revert = { id: 22, kind: 'REVERT', reviewer: me, createdAt: now, rowCount: 2, sheet: null, primary: null, detail: { revertOf: 30 } }
    mockFetch([{ path: '/projects/1/activity', body: { items: [glossary, emptyJob, revert] } }])
    render()
    expect(await screen.findByText('되돌림')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '2행' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '되돌리기' })).toHaveLength(1)
  })

  it('비어 있으면 안내 문구', async () => {
    mockFetch([{ path: '/projects/1/activity', body: { items: [] } }])
    render()
    expect(await screen.findByText('아직 변경 내역이 없습니다.')).toBeInTheDocument()
  })
})
