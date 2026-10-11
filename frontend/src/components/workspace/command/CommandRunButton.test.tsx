import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { CommandRun } from './commandRuns'
import { CommandRunButton, CommandRunNotice } from './CommandRunButton'

const run = (patch: Partial<CommandRun>): CommandRun => ({ id: 1, sheet: 'Skill', ask: { command: 'x' }, status: 'running', seen: false, ...patch })

describe('CommandRunButton', () => {
  it('실행 없음 → 평소 이름, 누르면 지금 시트로 연다', async () => {
    const onOpen = vi.fn()
    render(<CommandRunButton run={null} sheet="Skill" onOpen={onOpen} />)
    await userEvent.click(screen.getByRole('button', { name: '명령으로 고치기…' }))
    expect(onOpen).toHaveBeenCalledWith('Skill')
  })

  it('실행 중·안 본 결과·실패를 이름으로 알리고, 그 실행의 시트로 연다', async () => {
    const onOpen = vi.fn()
    const { rerender } = render(<CommandRunButton run={run({})} sheet="Skill" onOpen={onOpen} />)
    expect(screen.getByRole('button', { name: '명령 처리 중…' })).toBeInTheDocument()
    rerender(<CommandRunButton run={run({ status: 'done', sheet: 'Item' })} sheet="Skill" onOpen={onOpen} />)
    await userEvent.click(screen.getByRole('button', { name: '명령 결과 보기 · Item' }))
    expect(onOpen).toHaveBeenCalledWith('Item')
    rerender(<CommandRunButton run={run({ status: 'error' })} sheet="Skill" onOpen={onOpen} />)
    expect(screen.getByRole('button', { name: '명령 실패 · 보기' })).toBeInTheDocument()
  })

  it('본 결과면 평소 이름으로 돌아온다', () => {
    render(<CommandRunButton run={run({ status: 'done', seen: true })} sheet="Skill" onOpen={vi.fn()} />)
    expect(screen.getByRole('button', { name: '명령으로 고치기…' })).toBeInTheDocument()
  })
})

describe('CommandRunNotice', () => {
  it('창이 닫혀 있을 때 끝난 결과를 알리고 보기로 연다', async () => {
    const onOpen = vi.fn()
    render(<CommandRunNotice run={run({ status: 'done', plan: { summary: null, kind: 'FIX', ops: [], trail: [], rows: [] } })} dialogOpen={false} onOpen={onOpen} />)
    expect(screen.getByRole('status')).toHaveTextContent('명령 결과가 준비됐습니다 · Skill')
    await userEvent.click(screen.getByRole('button', { name: '보기' }))
    expect(onOpen).toHaveBeenCalledWith('Skill')
  })

  it('실행 중이거나, 창이 열려 있거나, 본 결과면 안 보인다', () => {
    const { rerender } = render(<CommandRunNotice run={run({})} dialogOpen={false} onOpen={vi.fn()} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    rerender(<CommandRunNotice run={run({ status: 'done' })} dialogOpen onOpen={vi.fn()} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    rerender(<CommandRunNotice run={run({ status: 'done', seen: true })} dialogOpen={false} onOpen={vi.fn()} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('실패도 알린다', () => {
    render(<CommandRunNotice run={run({ status: 'error', error: 'GPT가 작업을 끝내지 못했습니다.' })} dialogOpen={false} onOpen={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('명령이 실패했습니다 · Skill')
  })
})
