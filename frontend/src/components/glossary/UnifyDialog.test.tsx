import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { GlossaryVariant } from '../../api/types'
import { mockFetch } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'
import { UnifyDialog } from './UnifyDialog'

const v: GlossaryVariant = {
  find: '每日', kind: 'ALT', rows: 131, missingRows: 0, missing: [], standard: null,
  main: { ko: '일일', rows: 74, spacing: false, examples: [{ src: '每日任务', ko: '일일 임무' }] },
  alts: [
    { ko: '매일', rows: 49, spacing: false, examples: [{ src: '每日签到', ko: '매일 출석' }] },
    { ko: '하루에', rows: 5, spacing: false, examples: [{ src: '每日可领取一次', ko: '하루에 한 번 받을 수 있음' }] },
  ],
}
const saved = { entry: { id: 1, gameId: 7, kind: 'SRC', matchMode: 'PARTIAL', find: '每日', ko: null, note: '매일로 통일, 일일도 씀', oldKo: null, shared: false, updatedAt: '' }, applied: 5 }
const routes = () => [
  { path: '/games/7/glossary/apply/count', body: { count: 3 } },
  { method: 'POST', path: '/games/7/glossary/unify', body: saved },
]
const open = (onDone = vi.fn(), onClose = vi.fn()) => {
  renderWithProviders(<UnifyDialog gameId={7} v={v} onDone={onDone} onClose={onClose} />)
  return { onDone, onClose }
}

describe('UnifyDialog', () => {
  it('처음엔 주 표기로 맞추고 나머지를 모두 바꿀 표기로 체크해 둔다', async () => {
    mockFetch(routes())
    open()
    expect(screen.getByRole('dialog', { name: '每日 통일' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '일일 · 74행' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '매일' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '하루에' })).toBeChecked()
    expect(screen.queryByRole('checkbox', { name: '일일' })).not.toBeInTheDocument()
    expect(await screen.findAllByText('3행 바뀜')).toHaveLength(2)
  })

  it('맞출 표기를 바꾸면 주 표기가 바꿀 표기로 넘어가고, 하나만 체크하면 메모로 남는다고 알린다', async () => {
    const { calls } = mockFetch(routes())
    const { onDone } = open()
    await userEvent.click(screen.getByRole('radio', { name: '매일 · 49행' }))
    expect(screen.getByRole('checkbox', { name: '일일' })).toBeChecked()   // 그대로 누르면 일일 행까지 바뀐다(Review Focus 2)
    await userEvent.click(screen.getByRole('checkbox', { name: '일일' }))
    expect(screen.getByText('남긴 표기가 있어 단어장에는 메모로 남습니다.')).toBeInTheDocument()
    expect(screen.getByText('매일 한 번 받을 수 있음')).toBeInTheDocument()   // 하루에 예문의 바뀐 뒤
    await userEvent.click(screen.getByRole('button', { name: '통일' }))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ find: '每日', ko: '매일', replace: ['하루에'], keep: ['일일'] })
    expect(onDone).toHaveBeenCalledWith('매일로 통일 · 5행')
  })

  it('직접 입력한 표기로 맞출 수 있다', async () => {
    const { calls } = mockFetch(routes())
    open()
    await userEvent.click(screen.getByRole('radio', { name: '직접 입력' }))
    await userEvent.type(screen.getByRole('textbox', { name: '맞출 표기 직접 입력' }), '데일리')
    await userEvent.click(screen.getByRole('button', { name: '통일' }))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ find: '每日', ko: '데일리', replace: ['일일', '매일', '하루에'], keep: [] })
  })

  it('바꿀 표기를 하나도 고르지 않으면 통일할 수 없다', async () => {
    mockFetch(routes())
    open()
    await userEvent.click(screen.getByRole('checkbox', { name: '매일' }))
    await userEvent.click(screen.getByRole('checkbox', { name: '하루에' }))
    expect(screen.getByRole('button', { name: '통일' })).toBeDisabled()
  })

  it('서버가 거절하면 창 안에 메시지를 보여 주고 닫지 않는다', async () => {
    mockFetch([routes()[0], { method: 'POST', path: '/games/7/glossary/unify', status: 400, body: { message: '이미 단어장에 있는 원문입니다: 每日' } }])
    const { onDone, onClose } = open()
    await userEvent.click(screen.getByRole('button', { name: '통일' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 단어장에 있는 원문입니다: 每日')
    expect(onDone).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('Esc와 취소로 닫는다', async () => {
    mockFetch(routes())
    const { onClose } = open()
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})
