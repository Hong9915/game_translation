import { useSyncExternalStore } from 'react'
import { api } from '../../../api/client'
import type { CommandPlan, CommandRow, OpSpec } from '../../../api/types'
import { type ReviewTarget, screenOf } from '../../../lib/reviewTarget'

/** 미리 보기 요청 하나: GPT 명령, 작업 목록(도구 버튼·저장 명령·손본 작업), 태그 짝 검사, 길이 줄이기(묶음으로 나눠 GPT). */
export type CommandAsk = { command: string } | { ops: OpSpec[] } | { check: 'TAG_MISMATCH' } | { shorten: true }

/** seen = 결과(또는 실패)를 창에서 봤는가. 실행 중에는 의미 없다. sheet는 범위 이름(시트 이름 또는 "시트 3개"), target은 그 범위. */
export interface CommandRun {
  id: number
  sheet: string
  target: ReviewTarget
  ask: CommandAsk
  status: 'running' | 'done' | 'error'
  plan?: CommandPlan
  /** 길이 줄이기 진행: 줄여 본 행 / 길이 넘는 행. total이 null이면 아직 대상을 찾는 중. */
  progress?: { done: number; total: number | null }
  error?: string
  seen: boolean
}

/**
 * 명령으로 고치기 실행 보관소(2026-10-08). 프로젝트마다 마지막 실행 하나를 창 밖에 둔다 — 창을 닫아도 요청은 끝까지 돌고,
 * 다시 열면 결과가 그대로 있다. 화면(screenOf: 파일 화면은 프로젝트 id, 게임 화면은 -게임 id)마다 둔다. 리뷰 화면 버튼은 이걸 보고 "처리 중… / 결과 보기"를 보인다.
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

/** 새로 시작하면 그 화면의 앞 실행은 버린다(늦게 온 결과도 무시). */
export function startCommandRun(target: ReviewTarget, sheet: string, ask: CommandAsk) {
  const id = ++seq
  const projectId = screenOf(target)
  set(projectId, { id, sheet, target, ask, status: 'running', seen: false })
  const settle = (patch: Partial<CommandRun>) => {
    const now = runs.get(projectId)
    if (now?.id === id) set(projectId, { ...now, ...patch, seen: false })
  }
  const running = (patch: Partial<CommandRun>) => {
    const now = runs.get(projectId)
    if (now?.id === id) set(projectId, { ...now, ...patch })
  }
  const work = 'shorten' in ask
    ? shortenAll(target, (done, total) => running({ progress: { done, total } }), () => runs.get(projectId)?.id === id)
    : api.review(target).commandPlan(ask)
  work.then(
    (plan) => settle({ status: 'done', plan }),
    (e: unknown) => settle({ status: 'error', error: e instanceof Error ? e.message : String(e) }),
  )
}

export const SHORTEN_CHUNK = 12
const SHORTEN_PARALLEL = 3

/**
 * 길이 줄이기(2026-10-13): 길이 넘는 행을 받아 SHORTEN_CHUNK행씩 나눠 SHORTEN_PARALLEL개 요청을 함께 보낸다 — 한 요청에 수백 행을
 * GPT로 보내지 않고 진행을 보이려고. 묶음 하나가 실패해도 나머지는 살린다(모두 실패면 실패). 실행이 바뀌면 남은 묶음은 보내지 않는다.
 */
async function shortenAll(target: ReviewTarget, onProgress: (done: number, total: number | null) => void, current: () => boolean): Promise<CommandPlan> {
  const review = api.review(target)
  onProgress(0, null)
  const ids = (await review.commandPlan({ check: 'OVER_LENGTH' })).rows.map((r) => r.segmentId)
  const chunks: number[][] = []
  for (let i = 0; i < ids.length; i += SHORTEN_CHUNK) chunks.push(ids.slice(i, i + SHORTEN_CHUNK))
  const found: CommandRow[][] = chunks.map(() => [])
  let done = 0, failed = 0, next = 0
  let lastError: unknown = null
  onProgress(0, ids.length)
  const worker = async () => {
    while (next < chunks.length && current()) {
      const at = next++
      try { found[at] = (await review.commandPlan({ ops: [{ kind: 'SHORTEN' }], only: chunks[at] })).rows }
      catch (e) { failed += chunks[at].length; lastError = e }
      done += chunks[at].length
      if (current()) onProgress(done, ids.length)
    }
  }
  await Promise.all(Array.from({ length: SHORTEN_PARALLEL }, worker))
  if (ids.length > 0 && failed === ids.length) throw lastError
  const rows = found.flat()   // 묶음 순서 = 시트 순서
  const none = ids.length - failed - rows.length
  const summary = ids.length === 0 ? '길이 기준을 넘는 행이 없습니다.'
    : `길이 기준을 넘는 ${ids.length.toLocaleString()}행 가운데 ${rows.length.toLocaleString()}행을 줄였습니다.`
      + (none > 0 ? ` ${none.toLocaleString()}행은 더 짧은 후보가 없었습니다.` : '')
      + (failed > 0 ? ` ${failed.toLocaleString()}행은 GPT 오류로 건너뛰었습니다 — 다시 돌려 보세요.` : '')
  return { summary, kind: 'FIX', ops: [{ kind: 'SHORTEN' }], trail: [], rows }
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
