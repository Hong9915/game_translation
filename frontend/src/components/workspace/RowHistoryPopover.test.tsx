import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { RowHistoryEntry } from '../../api/types'
import { RowHistoryPopover } from './RowHistoryPopover'

const now = new Date().toISOString()
const entries: RowHistoryEntry[] = [
  { version: 2, author: 'HUMAN', text: '정의의 재판', createdAt: now, reviewer: { id: 1, name: '지민', color: '#1f4b99' }, activityKind: 'JOSA_FIX' },
  { version: 1, author: 'LLM', text: '정의의 심판', createdAt: now, reviewer: null, activityKind: null },
]

describe('RowHistoryPopover', () => {
  it('최신 판부터 누가·무슨 작업으로 남겼는지 보이고, 앞 판과 달라진 글자를 칠한다', async () => {
    render(<RowHistoryPopover x={0} y={0} rowNo={4} load={() => Promise.resolve(entries)} current="정의의 재판" onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: '4행 변경 내역' })
    const items = await within(dialog).findAllByRole('listitem')
    expect(items[0]).toHaveTextContent('지민· 조사 고침방금')
    expect(within(items[0]).getByText('재', { selector: 'ins' })).toBeInTheDocument()
    expect(within(items[0]).getByText('심', { selector: 'del' })).toBeInTheDocument()
    expect(items[1]).toHaveTextContent('GPT 번역')
    expect(items[1].querySelector('ins, del')).toBeNull()
  })

  it('불러오지 못하면 알리고, Esc로 닫는다', async () => {
    const onClose = vi.fn()
    render(<RowHistoryPopover x={0} y={0} rowNo={4} load={() => Promise.reject(new Error('행을 찾을 수 없습니다: 9'))} current={null} onClose={onClose} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('행을 찾을 수 없습니다: 9')
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('지금 본문과 다른 판만 되돌릴 수 있고, 누르면 그 글로 저장하고 닫는다', async () => {
    const onRestore = vi.fn()
    const onClose = vi.fn()
    render(<RowHistoryPopover x={0} y={0} rowNo={4} load={() => Promise.resolve(entries)} current="정의의 재판" onRestore={onRestore} onClose={onClose} />)
    const items = await screen.findAllByRole('listitem')
    expect(within(items[0]).queryByRole('button', { name: '이 글로 되돌리기' })).toBeNull()
    await userEvent.click(within(items[1]).getByRole('button', { name: '이 글로 되돌리기' }))
    expect(onRestore).toHaveBeenCalledWith('정의의 심판')
    expect(onClose).toHaveBeenCalled()
  })

  it('작업 중인 행(onRestore 없음)엔 되돌리기 단추가 없다', async () => {
    render(<RowHistoryPopover x={0} y={0} rowNo={4} load={() => Promise.resolve(entries)} current="정의의 재판" onClose={vi.fn()} />)
    await screen.findAllByRole('listitem')
    expect(screen.queryByRole('button', { name: '이 글로 되돌리기' })).toBeNull()
  })
})
