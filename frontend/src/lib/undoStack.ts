/** 되돌리기 한 단위: 한 번의 사용자 동작이 만든 변경 내역 id 묶음. */
export interface UndoUnit { label: string; activityIds: number[] }
export interface UndoState { past: UndoUnit[]; future: UndoUnit[] }

const MAX_PAST = 50

export const emptyUndo: UndoState = { past: [], future: [] }

/** 새 동작을 쌓는다. 바꾼 게 없으면 그대로, 쌓으면 다시 실행 목록은 비운다. */
export function pushUnit(s: UndoState, u: UndoUnit): UndoState {
  if (u.activityIds.length === 0) return s
  return { past: [...s.past, u].slice(-MAX_PAST), future: [] }
}

/** 가장 최근 단위를 꺼낸다. 없으면 [null, s]. */
export function popPast(s: UndoState): [UndoUnit | null, UndoState] {
  if (s.past.length === 0) return [null, s]
  return [s.past[s.past.length - 1], { ...s, past: s.past.slice(0, -1) }]
}

export function popFuture(s: UndoState): [UndoUnit | null, UndoState] {
  if (s.future.length === 0) return [null, s]
  return [s.future[s.future.length - 1], { ...s, future: s.future.slice(0, -1) }]
}

/** 되돌린 단위를 다시 실행 목록에 쌓는다(past는 건드리지 않음). */
export function toFuture(s: UndoState, u: UndoUnit): UndoState {
  if (u.activityIds.length === 0) return s
  return { ...s, future: [...s.future, u] }
}

/** 다시 실행한 단위를 되돌리기 목록에 쌓는다(future는 비우지 않음). */
export function toPast(s: UndoState, u: UndoUnit): UndoState {
  if (u.activityIds.length === 0) return s
  return { ...s, past: [...s.past, u].slice(-MAX_PAST) }
}

/** 되돌리기 목록에 넣되, 그 사이 새로 쌓인 below개보다 아래에 넣는다(순서 유지). below가 0이면 toPast와 같다. */
export function insertPast(s: UndoState, u: UndoUnit, below: number): UndoState {
  if (u.activityIds.length === 0) return s
  const at = Math.max(0, s.past.length - below)
  return { ...s, past: [...s.past.slice(0, at), u, ...s.past.slice(at)].slice(-MAX_PAST) }
}
