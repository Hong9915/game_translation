import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'
import { TagSpacingDialog } from './TagSpacingDialog'

const rows = [
  { segmentId: 11, rowNo: 4, src: '造成<color=red>100%</color>伤害', before: '대상에게<color=red>100%</color>피해', after: '대상에게 <color=red>100%</color> 피해' },
  { segmentId: 12, rowNo: 7, src: '攻击<color=red>10%</color>提升', before: '공격력<color=red>10%</color>증가', after: '공격력 <color=red>10%</color> 증가' },
  { segmentId: 13, rowNo: 9, src: '点击<color=#a5ff86>任务</color>', before: '보려면<color=#a5ff86>퀘스트 탭</color>을 클릭', after: '보려면 <color=#a5ff86>퀘스트 탭</color>을 클릭' },
]
const routes = (applied = 2) => [
  { path: '/review/7/tag-spacing?sheet=Skill', body: rows },
  { method: 'POST', path: '/review/7/tag-spacing', body: { applied } },
]
const render = (onFixed = vi.fn(), onClose = vi.fn()) => { renderWithProviders(<TagSpacingDialog target={{ kind: 'project', projectId: 7, sheet: 'Skill' }} label="Skill" onFixed={onFixed} onClose={onClose} />); return { onFixed, onClose } }

describe('TagSpacingDialog', () => {
  it('게임 범위면 게임 경로로 묻고 행 앞에 시트 이름을 붙인다', async () => {
    mockFetch([{ path: '/games/3/review/tag-spacing?sheets=5%2C6', body: [{ ...rows[0], projectId: 9, sheet: 'Item' }] }])
    renderWithProviders(<TagSpacingDialog target={{ kind: 'game', gameId: 3, sheetIds: [5, 6] }} label="시트 2개" onFixed={vi.fn()} onClose={vi.fn()} />)
    expect(await screen.findByText('Item 4행')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '태그 띄어쓰기' })).toHaveTextContent('태그 띄어쓰기 · 시트 2개')
  })

  it('후보 행을 전부 고른 채로 보이고, 새로 넣을 공백을 칠한다', async () => {
    mockFetch(routes())
    render()
    const row = await screen.findByRole('checkbox', { name: '대상에게 <color=red>100%</color> 피해' })
    expect(row).toBeChecked()
    expect(screen.getByText('3 / 3행 고름')).toBeInTheDocument()
    const item = screen.getByRole('listitem', { name: '대상에게 <color=red>100%</color> 피해' })
    expect(within(item).getAllByTitle('새로 넣는 공백')).toHaveLength(2)
    expect(within(item).getByText('4행')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '태그 띄어쓰기' })).toHaveTextContent('태그 띄어쓰기 · Skill')
  })

  it('뺀 행을 빼고 고친 뒤, 고친 행은 목록에서 접는다', async () => {
    const { calls } = mockFetch(routes(1))
    const { onFixed } = render()
    await userEvent.click(await screen.findByRole('checkbox', { name: '공격력 <color=red>10%</color> 증가' }))
    await userEvent.click(screen.getByRole('checkbox', { name: '보려면 <color=#a5ff86>퀘스트 탭</color>을 클릭' }))
    await userEvent.click(screen.getByRole('button', { name: '1행 고치기' }))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ items: [{ segmentId: 11, before: rows[0].before, after: rows[0].after }] })
    expect(await screen.findByRole('status')).toHaveTextContent('1행을 고쳤습니다.')
    expect(onFixed).toHaveBeenCalledWith(1)
    expect(screen.queryByRole('listitem', { name: '대상에게 <color=red>100%</color> 피해' })).not.toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: '공격력 <color=red>10%</color> 증가' })).toBeInTheDocument()
  })

  it('건너뛴 행이 있으면 알린다', async () => {
    mockFetch(routes(1))
    render()
    await screen.findByRole('checkbox', { name: '대상에게 <color=red>100%</color> 피해' })
    await userEvent.click(screen.getByRole('checkbox', { name: '보려면 <color=#a5ff86>퀘스트 탭</color>을 클릭' }))
    await userEvent.click(screen.getByRole('button', { name: '2행 고치기' }))
    expect(await screen.findByRole('status')).toHaveTextContent('1행을 고쳤습니다. 1행은 그 사이 번역이 바뀌었거나 확정돼 건너뛰었습니다.')
  })

  it('전체 선택을 풀면 고칠 수 없고, 검색으로 좁힌다', async () => {
    mockFetch(routes())
    render()
    await screen.findByRole('checkbox', { name: '대상에게 <color=red>100%</color> 피해' })
    await userEvent.click(screen.getByRole('checkbox', { name: '보이는 행 전체 선택' }))
    expect(screen.getByRole('button', { name: '0행 고치기' })).toBeDisabled()
    await userEvent.type(screen.getByRole('searchbox', { name: '원문·번역 검색' }), '퀘스트')
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('Esc나 닫기로 닫는다', async () => {
    mockFetch(routes())
    const { onClose } = render()
    await screen.findByRole('checkbox', { name: '대상에게 <color=red>100%</color> 피해' })
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('검사기에 연결하지 못하면 서버 메시지를 보인다', async () => {
    mockFetch([{ path: '/review/7/tag-spacing', status: 409, body: { message: '띄어쓰기 검사기에 연결하지 못했습니다. 잠시 뒤 다시 열어 주세요.' } }])
    render()
    expect(await screen.findByRole('alert')).toHaveTextContent('띄어쓰기 검사기에 연결하지 못했습니다.')
  })
})
