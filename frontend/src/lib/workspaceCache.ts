import type { MarksPatch, ReviewEvent, SegmentRow, WorkspaceRow, WorkspaceRowsResponse } from '../api/types'

const norm = (s: string | null | undefined) => (s ?? '').trim()

/** 저장 응답과 SSE 이벤트는 옛 검토 API의 SegmentRow 모양이다. 그리드 행에 그 값들만 덮어쓴다(원문·기존 한국어·체크·비고는 안 바뀐다). */
export function rowFromSegment(prev: WorkspaceRow, row: SegmentRow): WorkspaceRow {
  const fix = row.currentText
  return {
    ...prev, fix, changed: norm(fix) !== norm(prev.existingKo), termMiss: row.termMiss ?? [], assignee: row.assignee ?? null,
    // 번역이 바뀌면 서버 트리거가 조사 제안을 지우고 다시 검사한다 — 옛 위치의 제안을 남기지 않는다
    josaHints: fix === prev.fix ? prev.josaHints : [],
    lastEditor: row.lastEditor ?? null, lastEditedAt: row.lastEditedAt ?? null, history: row.history,
  }
}

/** 체크·비고 변경을 행에 반영한다. 서버(ReviewMarkService)와 같은 규칙: 준 칸만 바뀐다(체크끼리 서로 독립). */
export function applyMarks(row: WorkspaceRow, patch: MarksPatch): WorkspaceRow {
  return {
    ...row,
    ...(patch.review1 !== undefined ? { review1: patch.review1 } : {}),
    ...(patch.review2 !== undefined ? { review2: patch.review2 } : {}),
    ...(patch.review3 !== undefined ? { review3: patch.review3 } : {}),
    ...(patch.needsCheck !== undefined ? { needsCheck: patch.needsCheck } : {}),
    ...(patch.confirmed !== undefined ? { confirmed: patch.confirmed } : {}),
    ...(patch.finalMark !== undefined ? { finalMark: patch.finalMark } : {}),
    ...(patch.note !== undefined ? { note: patch.note } : {}),
  }
}

const MARK_FIELDS = ['review1', 'review2', 'review3', 'needsCheck', 'confirmed', 'finalMark', 'note'] as const

/**
 * 체크 응답에서 체크·비고 칸만 가져온다. 응답 행 전체로 바꾸면, 체크 요청이 Fix 저장보다 먼저 행을 읽고 늦게 도착했을 때
 * 방금 저장한 Fix·이력이 옛 값으로 돌아간다.
 */
export function withMarksOf(cached: WorkspaceRow, response: WorkspaceRow): WorkspaceRow {
  return { ...cached, review1: response.review1, review2: response.review2, review3: response.review3, needsCheck: response.needsCheck, confirmed: response.confirmed, finalMark: response.finalMark, note: response.note }
}

/**
 * 실패한 체크 요청을 되돌린다. 그 요청이 바꾼 칸만, 그리고 그 칸이 아직 이 요청의 낙관적 값일 때만 누르기 전 값으로 돌린다 —
 * 그 사이 성공한 다른 요청(다른 칸, 또는 같은 칸을 다시 누른 것)을 덮지 않게.
 */
export function rollbackMarks(current: WorkspaceRow, prev: WorkspaceRow, patch: MarksPatch): WorkspaceRow {
  const optimistic = applyMarks(prev, patch)
  const touched = MARK_FIELDS.filter((k) => optimistic[k] !== prev[k])
  if (touched.length === 0 || touched.some((k) => current[k] !== optimistic[k])) return current
  const out = { ...current }
  for (const k of touched) (out as Record<string, unknown>)[k] = prev[k]
  return out
}

/** 실시간 이벤트를 목록 캐시 한 페이지에 반영한다. 이 페이지에 없는 행이면 같은 객체를 돌려줘 다시 그리지 않는다. */
export function patchRows(data: WorkspaceRowsResponse, ev: ReviewEvent): WorkspaceRowsResponse {
  if (ev.type === 'segment-updated') {
    if (!data.rows.some((r) => r.id === ev.row.id)) return data
    return { ...data, rows: data.rows.map((r) => (r.id === ev.row.id ? rowFromSegment(r, ev.row) : r)) }
  }
  if (ev.type === 'assigned') {
    const ids = new Set(ev.segmentIds)
    if (!data.rows.some((r) => ids.has(r.id))) return data
    return { ...data, rows: data.rows.map((r) => (ids.has(r.id) ? { ...r, assignee: ev.assignee } : r)) }
  }
  if (ev.type === 'marks') {
    const ids = new Set(ev.segmentIds)
    if (!data.rows.some((r) => ids.has(r.id))) return data
    // 서버가 실제로 쓴 값을 보내므로 그대로 쓴다(null은 안 바뀐 칸)
    const patch: MarksPatch = {
      ...(ev.review1 !== null ? { review1: ev.review1 } : {}),
      ...(ev.review2 !== null ? { review2: ev.review2 } : {}),
      ...(ev.review3 != null ? { review3: ev.review3 } : {}),
      ...(ev.needsCheck !== null ? { needsCheck: ev.needsCheck } : {}),
      ...(ev.confirmed !== null ? { confirmed: ev.confirmed } : {}),
      ...(ev.finalMark != null ? { finalMark: ev.finalMark } : {}),
      ...(ev.noteChanged ? { note: ev.note } : {}),
    }
    return { ...data, rows: data.rows.map((r) => (ids.has(r.id) ? applyMarks(r, patch) : r)) }
  }
  return data
}
