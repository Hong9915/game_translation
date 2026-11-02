import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { CommandRow } from '../../../api/types'
import { CheckRows, FixRows } from './CommandRows'

const fixRows: CommandRow[] = [
  { segmentId: 1, rowNo: 4, src: '九尾狐来了', before: '구미호이 왔다', after: '여우가 왔다', by: ['REPLACE', 'JOSA'], warnings: [], mismatch: null },
  { segmentId: 2, rowNo: 5, src: '{0}点', before: '{0}점 - 1', after: '점-1', by: ['REPLACE'],
    warnings: [{ kind: 'TAG', text: '태그·자리표시자 짝이 깨집니다' }, { kind: 'JOSA', text: '여우이 → 여우가' }], mismatch: null },
]
const checkRows: CommandRow[] = [
  { segmentId: 3, rowNo: 9, src: '造成{0}点', before: '{1} 피해', after: null, by: [], warnings: [], mismatch: { src: ['{0}'], ko: ['{1}'] } },
]

describe('FixRows', () => {
  it('변경 전·후, 바꾼 작업, 경고를 보인다', () => {
    render(<FixRows rows={fixRows} skipped={new Set()} onToggle={vi.fn()} onSetMany={vi.fn()} />)
    const first = screen.getByRole('listitem', { name: '여우가 왔다' })
    expect(within(first).getByText('찾아 바꾸기')).toBeInTheDocument()
    expect(within(first).getByText('조사')).toBeInTheDocument()
    const second = screen.getByRole('listitem', { name: '점-1' })
    expect(within(second).getByText('태그·자리표시자 짝이 깨집니다')).toBeInTheDocument()
    expect(within(second).getByText('조사: 여우이 → 여우가')).toBeInTheDocument()
  })

  it('체크를 누르면 onToggle', async () => {
    const onToggle = vi.fn()
    render(<FixRows rows={fixRows} skipped={new Set([2])} onToggle={onToggle} onSetMany={vi.fn()} />)
    expect(screen.getByRole('checkbox', { name: '점-1' })).not.toBeChecked()
    await userEvent.click(screen.getByRole('checkbox', { name: '여우가 왔다' }))
    expect(onToggle).toHaveBeenCalledWith(1)
  })
})

describe('FixRows 길이 줄이기', () => {
  it('글자 수 전 → 후와 목표를 보이고, 목표를 넘으면 주황으로 칠한다', () => {
    const rows: CommandRow[] = [
      { segmentId: 7, rowNo: 2, src: '陷阱伤害', before: '함정에 의해 받는 피해량', after: '함정 피해', by: ['SHORTEN'], warnings: [], mismatch: null, target: 8 },
      { segmentId: 8, rowNo: 3, src: '陷阱', before: '함정에 의해 받는 피해량', after: '함정에 받는 피해', by: ['SHORTEN'],
        warnings: [{ kind: 'LENGTH', text: '목표 2자보다 깁니다' }], mismatch: null, target: 2 },
    ]
    render(<FixRows rows={rows} skipped={new Set()} onToggle={vi.fn()} onSetMany={vi.fn()} />)
    const fit = within(screen.getByRole('listitem', { name: '함정 피해' }))
    expect(fit.getByTestId('length')).toHaveTextContent('10 → 4자 (목표 8)')
    expect(fit.getByText('길이')).toBeInTheDocument()
    const over = within(screen.getByRole('listitem', { name: '함정에 받는 피해' }))
    expect(over.getByText('7자')).toHaveClass('text-orange')
    expect(over.queryByText('목표 2자보다 깁니다')).toBeNull()
  })
})

describe('CheckRows', () => {
  it('원문·번역과 짝 안 맞는 토큰을 보인다', () => {
    render(<CheckRows rows={checkRows} skipped={new Set()} onToggle={vi.fn()} onSetMany={vi.fn()} />)
    const item = screen.getByRole('listitem', { name: '9행' })
    expect(within(item).getByTitle('번역에 없음')).toHaveTextContent('{0}')
    expect(within(item).getByTitle('원문에 없음')).toHaveTextContent('{1}')
  })
})

describe('FixRows 묶음', () => {
  const level = (id: number, a: number, b: number): CommandRow => (
    { segmentId: id, rowNo: id, src: `关卡 ${a}-${b}`, before: `Level ${a}·${b}`, after: `스테이지 ${a}-${b}`, by: ['TERM'], warnings: [], mismatch: null })
  const rows: CommandRow[] = [level(1, 54, 14), level(2, 60, 1), level(3, 7, 3),
    { segmentId: 9, rowNo: 9, src: '通关关卡', before: '通关关卡', after: '스테이지 클리어', by: ['TERM'], warnings: [], mismatch: null }]

  it('숫자만 다른 행은 ×N 한 줄로 묶고, 묶음 체크는 안의 행 전체를 켜고 끈다', async () => {
    const onSetMany = vi.fn()
    render(<FixRows rows={rows} skipped={new Set()} onToggle={vi.fn()} onSetMany={onSetMany} />)
    expect(screen.getByText('×3')).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: '스테이지 60-1' })).not.toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: '스테이지 클리어' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: '묶음 스테이지 54-14 3행' }))
    expect(onSetMany).toHaveBeenCalledWith([1, 2, 3], true)
  })

  it('일부만 고른 묶음은 체크가 반쯤이고, 펼치면 안의 행을 하나씩 고른다', async () => {
    const onToggle = vi.fn()
    render(<FixRows rows={rows} skipped={new Set([2])} onToggle={onToggle} onSetMany={vi.fn()} />)
    const box = screen.getByRole('checkbox', { name: '묶음 스테이지 54-14 3행' }) as HTMLInputElement
    expect(box.checked).toBe(false)
    expect(box.indeterminate).toBe(true)
    await userEvent.click(screen.getByRole('button', { name: '펼치기' }))
    expect(screen.getByRole('checkbox', { name: '스테이지 60-1' })).not.toBeChecked()
    await userEvent.click(screen.getByRole('checkbox', { name: '스테이지 7-3' }))
    expect(onToggle).toHaveBeenCalledWith(3)
  })
})
