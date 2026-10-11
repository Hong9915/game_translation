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
    render(<FixRows rows={fixRows} skipped={new Set()} onToggle={vi.fn()} />)
    const first = screen.getByRole('listitem', { name: '여우가 왔다' })
    expect(within(first).getByText('찾아 바꾸기')).toBeInTheDocument()
    expect(within(first).getByText('조사')).toBeInTheDocument()
    const second = screen.getByRole('listitem', { name: '점-1' })
    expect(within(second).getByText('태그·자리표시자 짝이 깨집니다')).toBeInTheDocument()
    expect(within(second).getByText('조사: 여우이 → 여우가')).toBeInTheDocument()
  })

  it('체크를 누르면 onToggle', async () => {
    const onToggle = vi.fn()
    render(<FixRows rows={fixRows} skipped={new Set([2])} onToggle={onToggle} />)
    expect(screen.getByRole('checkbox', { name: '점-1' })).not.toBeChecked()
    await userEvent.click(screen.getByRole('checkbox', { name: '여우가 왔다' }))
    expect(onToggle).toHaveBeenCalledWith(1)
  })
})

describe('CheckRows', () => {
  it('원문·번역과 짝 안 맞는 토큰을 보인다', () => {
    render(<CheckRows rows={checkRows} skipped={new Set()} onToggle={vi.fn()} />)
    const item = screen.getByRole('listitem', { name: '9행' })
    expect(within(item).getByTitle('번역에 없음')).toHaveTextContent('{0}')
    expect(within(item).getByTitle('원문에 없음')).toHaveTextContent('{1}')
  })
})
