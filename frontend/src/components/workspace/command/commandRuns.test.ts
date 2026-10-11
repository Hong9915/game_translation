import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../../api/client'
import type { CommandPlan } from '../../../api/types'
import { getCommandRun, markCommandRunSeen, resetCommandRuns, startCommandRun } from './commandRuns'

const plan = (summary: string): CommandPlan => ({ summary, kind: 'FIX', ops: [{ kind: 'JOSA' }], trail: [], rows: [] })
const deferred = <T,>() => {
  let resolve!: (v: T) => void, reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

describe('commandRuns', () => {
  afterEach(() => { resetCommandRuns(); vi.restoreAllMocks() })

  it('창과 상관없이 끝까지 돌고, 끝나면 안 본 결과로 남는다', async () => {
    const d = deferred<CommandPlan>()
    vi.spyOn(api.workspace, 'commandPlan').mockReturnValue(d.promise)
    startCommandRun(7, 'Skill', { command: '구미호를 여우로' })
    expect(getCommandRun(7)).toMatchObject({ sheet: 'Skill', status: 'running', ask: { command: '구미호를 여우로' } })
    d.resolve(plan('끝'))
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    expect(getCommandRun(7)).toMatchObject({ seen: false, plan: { summary: '끝' } })
    markCommandRunSeen(7)
    expect(getCommandRun(7)?.seen).toBe(true)
  })

  it('실패도 이유와 함께 안 본 결과로 남는다', async () => {
    vi.spyOn(api.workspace, 'commandPlan').mockRejectedValue(new Error('GPT가 작업을 끝내지 못했습니다.'))
    startCommandRun(7, 'Skill', { ops: [{ kind: 'JOSA' }] })
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('error'))
    expect(getCommandRun(7)).toMatchObject({ error: 'GPT가 작업을 끝내지 못했습니다.', seen: false })
  })

  it('새로 시작하면 앞 실행의 늦은 결과는 버린다', async () => {
    const first = deferred<CommandPlan>(), second = deferred<CommandPlan>()
    vi.spyOn(api.workspace, 'commandPlan').mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    startCommandRun(7, 'Skill', { command: '첫째' })
    startCommandRun(7, 'Skill', { command: '둘째' })
    second.resolve(plan('둘째 결과'))
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    first.resolve(plan('첫째 결과'))
    await new Promise((r) => setTimeout(r, 0))
    expect(getCommandRun(7)?.plan?.summary).toBe('둘째 결과')
  })

  it('프로젝트마다 따로 둔다', () => {
    vi.spyOn(api.workspace, 'commandPlan').mockReturnValue(new Promise(() => {}))
    startCommandRun(7, 'Skill', { command: 'a' })
    expect(getCommandRun(8)).toBeNull()
  })
})
