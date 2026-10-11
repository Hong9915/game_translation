import { useSyncExternalStore } from 'react'
import { api } from '../../../api/client'
import type { CommandPlan, OpSpec } from '../../../api/types'

/** 미리 보기 요청 하나: GPT 명령, 작업 목록(도구 버튼·저장 명령·손본 작업), 태그 짝 검사. */
export type CommandAsk = { command: string } | { ops: OpSpec[] } | { check: 'TAG_MISMATCH' }

/** seen = 결과(또는 실패)를 창에서 봤는가. 실행 중에는 의미 없다. */
export interface CommandRun {
  id: number
  sheet: string
  ask: CommandAsk
  status: 'running' | 'done' | 'error'
  plan?: CommandPlan
  error?: string
  seen: boolean
}

/**
 * 명령으로 고치기 실행 보관소(2026-10-08). 프로젝트마다 마지막 실행 하나를 창 밖에 둔다 — 창을 닫아도 요청은 끝까지 돌고,
 * 다시 열면 결과가 그대로 있다. 리뷰 화면 버튼은 이걸 보고 "처리 중… / 결과 보기"를 보인다.
 * 브라우저 메모리에만 있어 새로고침하면 사라진다(서버 작업으로 두지 않기로 함).
 */
const runs = new Map<number, CommandRun>()
const listeners = new Set<() => void>()
let seq = 0

function set(projectId: number, run: CommandRun | null) {
  if (run) runs.set(projectId, run)
  else runs.delete(projectId)
  listeners.forEach((l) => l())
}

/** 새로 시작하면 앞 실행은 버린다(늦게 온 결과도 무시). */
export function startCommandRun(projectId: number, sheet: string, ask: CommandAsk) {
  const id = ++seq
  set(projectId, { id, sheet, ask, status: 'running', seen: false })
  const settle = (patch: Partial<CommandRun>) => {
    const now = runs.get(projectId)
    if (now?.id === id) set(projectId, { ...now, ...patch, seen: false })
  }
  api.workspace.commandPlan(projectId, { sheet, ...ask }).then(
    (plan) => settle({ status: 'done', plan }),
    (e: unknown) => settle({ status: 'error', error: e instanceof Error ? e.message : String(e) }),
  )
}

export function markCommandRunSeen(projectId: number) {
  const now = runs.get(projectId)
  if (now && !now.seen && now.status !== 'running') set(projectId, { ...now, seen: true })
}

export function getCommandRun(projectId: number): CommandRun | null {
  return runs.get(projectId) ?? null
}

export function useCommandRun(projectId: number): CommandRun | null {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => { listeners.delete(l) } },
    () => getCommandRun(projectId),
  )
}

/** 테스트용. */
export function resetCommandRuns() {
  runs.clear()
  listeners.forEach((l) => l())
}
