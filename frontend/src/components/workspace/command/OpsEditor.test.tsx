import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { OpSpec } from '../../../api/types'
import { OpsEditor, opLabel } from './OpsEditor'

const ops: OpSpec[] = [
  { kind: 'REPLACE', filter: { in: 'ko', contains: '구미호' }, steps: [{ find: '구미호', replace: '여우', regex: false }] },
  { kind: 'JOSA' },
]

describe('OpsEditor', () => {
  it('작업을 차례 칩으로 말해 준다', () => {
    render(<OpsEditor ops={ops} onChange={vi.fn()} editing={false} />)
    expect(screen.getByText('① 찾아 바꾸기 · 번역에 “구미호”가 든 행')).toBeInTheDocument()
    expect(screen.getByText('② 조사 맞추기')).toBeInTheDocument()
    expect(opLabel({ kind: 'UNIFY_SAME_SOURCE' })).toBe('같은 원문 통일')
  })

  it('칩을 빼면 그 작업 없이 돌려준다', async () => {
    const onChange = vi.fn()
    render(<OpsEditor ops={ops} onChange={onChange} editing={false} />)
    await userEvent.click(screen.getByRole('button', { name: '조사 맞추기 빼기' }))
    expect(onChange).toHaveBeenCalledWith([ops[0]])
  })

  it('편집 중이면 찾아 바꾸기 단계를 고친다', async () => {
    const onChange = vi.fn()
    render(<OpsEditor ops={ops} onChange={onChange} editing />)
    const replace = screen.getByRole('textbox', { name: '1단계 바꿀 꼴' })
    await userEvent.type(replace, '!')
    expect(onChange).toHaveBeenLastCalledWith([{ ...ops[0], steps: [{ find: '구미호', replace: '여우!', regex: false }] }, ops[1]])
  })
})
