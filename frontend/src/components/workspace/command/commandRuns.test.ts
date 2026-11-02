import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../../api/client'
import type { CommandPlan, CommandRow } from '../../../api/types'
import type { ReviewTarget } from '../../../lib/reviewTarget'
import { SHORTEN_CHUNK, getCommandRun, markCommandRunSeen, resetCommandRuns, startCommandRun } from './commandRuns'

const plan = (summary: string): CommandPlan => ({ summary, kind: 'FIX', ops: [{ kind: 'JOSA' }], trail: [], rows: [] })
const T7: ReviewTarget = { kind: 'project', projectId: 7, sheet: 'Skill' }
/** api.review(target)는 부를 때마다 새 객체라 commandPlan만 바꾼 객체를 돌려준다. */
const mockPlan = () => {
  const fn = vi.fn()
  vi.spyOn(api, 'review').mockReturnValue({ commandPlan: fn } as unknown as ReturnType<typeof api.review>)
  return fn
}
const deferred = <T,>() => {
  let resolve!: (v: T) => void, reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

describe('commandRuns', () => {
  afterEach(() => { resetCommandRuns(); vi.restoreAllMocks() })

  it('창과 상관없이 끝까지 돌고, 끝나면 안 본 결과로 남는다', async () => {
    const d = deferred<CommandPlan>()
    mockPlan().mockReturnValue(d.promise)
    startCommandRun(T7, 'Skill', { command: '구미호를 여우로' })
    expect(getCommandRun(7)).toMatchObject({ sheet: 'Skill', status: 'running', ask: { command: '구미호를 여우로' } })
    d.resolve(plan('끝'))
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    expect(getCommandRun(7)).toMatchObject({ seen: false, plan: { summary: '끝' } })
    markCommandRunSeen(7)
    expect(getCommandRun(7)?.seen).toBe(true)
  })

  it('실패도 이유와 함께 안 본 결과로 남는다', async () => {
    mockPlan().mockRejectedValue(new Error('GPT가 작업을 끝내지 못했습니다.'))
    startCommandRun(T7, 'Skill', { ops: [{ kind: 'JOSA' }] })
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('error'))
    expect(getCommandRun(7)).toMatchObject({ error: 'GPT가 작업을 끝내지 못했습니다.', seen: false })
  })

  it('새로 시작하면 앞 실행의 늦은 결과는 버린다', async () => {
    const first = deferred<CommandPlan>(), second = deferred<CommandPlan>()
    mockPlan().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    startCommandRun(T7, 'Skill', { command: '첫째' })
    startCommandRun(T7, 'Skill', { command: '둘째' })
    second.resolve(plan('둘째 결과'))
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    first.resolve(plan('첫째 결과'))
    await new Promise((r) => setTimeout(r, 0))
    expect(getCommandRun(7)?.plan?.summary).toBe('둘째 결과')
  })

  it('게임 화면은 -게임 id 자리에 따로 둔다', () => {
    mockPlan().mockReturnValue(new Promise(() => {}))
    startCommandRun({ kind: 'game', gameId: 7, sheetIds: [] }, '시트 2개', { command: 'a' })
    expect(getCommandRun(-7)).toMatchObject({ sheet: '시트 2개', target: { kind: 'game', gameId: 7 } })
    expect(getCommandRun(7)).toBeNull()
  })

  it('프로젝트마다 따로 둔다', () => {
    mockPlan().mockReturnValue(new Promise(() => {}))
    startCommandRun(T7, 'Skill', { command: 'a' })
    expect(getCommandRun(8)).toBeNull()
  })

  it('길이 줄이기는 길이 넘는 행을 묶음으로 나눠 줄이고, 진행과 요약을 남긴다', async () => {
    const ids = Array.from({ length: SHORTEN_CHUNK + 3 }, (_, i) => i + 1)
    const row = (id: number): CommandRow => ({ segmentId: id, rowNo: id, src: '原', before: '아주 긴 번역', after: '짧게', by: ['SHORTEN'], warnings: [], mismatch: null, target: 2 })
    const fn = mockPlan()
    fn.mockImplementation(async (body: { check?: string; only?: number[] }) => {
      if (body.check === 'OVER_LENGTH') return { summary: null, kind: 'CHECK', ops: null, trail: [], rows: ids.map(row) }
      // 첫 행은 더 짧은 후보가 없어 빠진다
      return { summary: null, kind: 'FIX', ops: [{ kind: 'SHORTEN' }], trail: [], rows: (body.only ?? []).filter((i) => i !== 1).map(row) }
    })
    startCommandRun(T7, 'Skill', { shorten: true })
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('done'))
    const run = getCommandRun(7)!
    expect(fn).toHaveBeenCalledTimes(3)
    expect(fn).toHaveBeenCalledWith({ ops: [{ kind: 'SHORTEN' }], only: ids.slice(0, SHORTEN_CHUNK) })
    expect(run.progress).toEqual({ done: ids.length, total: ids.length })
    expect(run.plan?.rows.map((r) => r.segmentId)).toEqual(ids.slice(1))
    expect(run.plan?.ops).toEqual([{ kind: 'SHORTEN' }])
    expect(run.plan?.summary).toBe(`길이 기준을 넘는 ${ids.length}행 가운데 ${ids.length - 1}행을 줄였습니다. 1행은 더 짧은 후보가 없었습니다.`)
  })

  it('길이 줄이기 묶음이 모두 실패하면 실패로 남는다', async () => {
    const fn = mockPlan()
    fn.mockImplementation(async (body: { check?: string }) => {
      if (body.check === 'OVER_LENGTH') return { summary: null, kind: 'CHECK', ops: null, trail: [], rows: [{ segmentId: 1 }] }
      throw new Error('GPT에 연결하지 못했습니다.')
    })
    startCommandRun(T7, 'Skill', { shorten: true })
    await vi.waitFor(() => expect(getCommandRun(7)?.status).toBe('error'))
    expect(getCommandRun(7)?.error).toBe('GPT에 연결하지 못했습니다.')
  })
})
