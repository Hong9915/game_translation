import { describe, expect, it } from 'vitest'
import type { SegmentRow, WorkspaceRow, WorkspaceRowsResponse } from '../api/types'
import { applyMarks, patchRows, rollbackMarks, rowFromSegment, withMarksOf } from './workspaceCache'

const jimin = { id: 7, name: '지민', color: '#0c8599' }
const row = (id: number, over: Partial<WorkspaceRow> = {}): WorkspaceRow => ({
  id, rowNo: id + 1, pairIndex: 0, indexLabel: `UI!${id + 1}`, keyText: `K${id}`, src: '确认', en: 'Confirm', existingKo: '확인', fix: '확인',
  changed: false, termMiss: [], josaHints: [], assignee: null, review1: false, review2: false, needsCheck: false, confirmed: false, finalReview: false, finalMark: false, note: null, lastEditor: null, lastEditedAt: null, history: [], dupCount: 0, ...over,
})
const seg = (id: number, over: Partial<SegmentRow> = {}): SegmentRow => ({
  id, indexLabel: `UI!${id + 1}`, srcText: '确认', enText: 'Confirm', currentText: '확인', lenKo: 2, lenSrc: 2, flagged: false,
  history: [{ version: 0, author: 'EXISTING', text: '확인', createdAt: '2026-09-23T00:00:00Z' }], ...over,
})
const data = (): WorkspaceRowsResponse => ({ rows: [row(1), row(2)], page: 0, size: 500, total: 2 })

describe('rowFromSegment', () => {
  it('takes fix, assignee, editor and history from the segment, keeps marks and recomputes changed', () => {
    const next = rowFromSegment(row(1, { review1: true, note: '메모' }), seg(1, { currentText: '확인 ', done: true, assignee: jimin, lastEditor: jimin, lastEditedAt: '2026-09-23T01:00:00Z', termMiss: ['强化→Enhance'] }))
    expect(next).toMatchObject({ fix: '확인 ', changed: false, review1: true, note: '메모', assignee: jimin, lastEditor: jimin, termMiss: ['强化→Enhance'] })
    expect(next).not.toHaveProperty('done')
    expect(next.history).toHaveLength(1)
    expect(rowFromSegment(row(1), seg(1, { currentText: '확인함' })).changed).toBe(true)
  })
})

describe('rowFromSegment josa hints', () => {
  // 리뷰 Important 3: 직접 고친 뒤에도 옛 조사 배지가 남아, 옛 위치로 엉뚱한 문구를 보이던 것
  const hint = { at: 4, from: '생명를', to: '생명을', kind: 'HANGUL' as const }
  it('drops josa hints when the fix text changes', () => {
    expect(rowFromSegment(row(1, { fix: '아군의 생명를', josaHints: [hint] }), seg(1, { currentText: '아군의 생명을' })).josaHints).toEqual([])
  })
  it('keeps josa hints when the fix text is the same', () => {
    expect(rowFromSegment(row(1, { fix: '아군의 생명를', josaHints: [hint] }), seg(1, { currentText: '아군의 생명를' })).josaHints).toEqual([hint])
  })
})

describe('patchRows', () => {
  it('replaces the matching row on segment-updated and leaves other pages untouched', () => {
    const d = data()
    const out = patchRows(d, { type: 'segment-updated', by: 7, row: seg(2, { currentText: '확인함' }) })
    expect(out.rows[1].fix).toBe('확인함')
    expect(out.rows[1].changed).toBe(true)
    expect(out.rows[0]).toBe(d.rows[0])
    expect(patchRows(d, { type: 'segment-updated', by: 7, row: seg(99) })).toBe(d)
  })
  it('sets the assignee on assigned rows', () => {
    const out = patchRows(data(), { type: 'assigned', by: 7, segmentIds: [1, 99], assignee: jimin })
    expect(out.rows[0].assignee).toEqual(jimin)
    expect(out.rows[1].assignee).toBeNull()
  })
  it('applies marks events to the listed rows only, leaving null fields alone', () => {
    const d: WorkspaceRowsResponse = { ...data(), rows: [row(1, { needsCheck: true, note: '옛 메모' }), row(2)] }
    const out = patchRows(d, { type: 'marks', by: 7, segmentIds: [1], review1: true, review2: true, needsCheck: null, confirmed: null, note: null, noteChanged: false })
    expect(out.rows[0]).toMatchObject({ review1: true, review2: true, needsCheck: true, confirmed: false, note: '옛 메모' })
    expect(out.rows[1]).toBe(d.rows[1])
    const cleared = patchRows(out, { type: 'marks', by: 7, segmentIds: [1], review1: null, review2: null, needsCheck: null, confirmed: null, note: null, noteChanged: true })
    expect(cleared.rows[0].note).toBeNull()
    expect(patchRows(d, { type: 'marks', by: 7, segmentIds: [99], review1: true, review2: true, needsCheck: null, confirmed: null, note: null, noteChanged: false })).toBe(d)
  })
  it('applies a finalMark marks event and keeps finalMark from a marks response', () => {
    const d: WorkspaceRowsResponse = { ...data(), rows: [row(1, { finalReview: true, finalMark: false }), row(2)] }
    const out = patchRows(d, { type: 'marks', by: 7, segmentIds: [1], review1: null, review2: null, needsCheck: null, confirmed: null, finalMark: true, note: null, noteChanged: false })
    expect(out.rows[0].finalMark).toBe(true)
    expect(withMarksOf(row(1, { finalMark: false }), row(1, { finalMark: true })).finalMark).toBe(true)
    const prev = row(1, { finalMark: false })
    expect(rollbackMarks(applyMarks(prev, { finalMark: true }), prev, { finalMark: true }).finalMark).toBe(false)
  })
  it('ignores presence and refresh', () => {
    const d = data()
    expect(patchRows(d, { type: 'presence', reviewers: [] })).toBe(d)
    expect(patchRows(d, { type: 'refresh', by: null })).toBe(d)
  })
})

describe('applyMarks', () => {
  it('changes only the given fields — 1차 and 2차 are independent — and sets note', () => {
    expect(applyMarks(row(1), { review1: true })).toMatchObject({ review1: true, review2: false })
    expect(applyMarks(row(1, { review1: true, review2: true }), { review1: false })).toMatchObject({ review1: false, review2: true })
    expect(applyMarks(row(1, { review1: true }), { review2: true })).toMatchObject({ review1: true, review2: true })
    expect(applyMarks(row(1), { needsCheck: true, note: '메모' })).toMatchObject({ review1: false, needsCheck: true, note: '메모' })
  })
})

describe('withMarksOf', () => {
  it('takes only the mark and note fields from the response, keeping the cached Fix', () => {
    const cached = row(1, { fix: '방금 저장한 글', changed: true })
    const stale = row(1, { fix: '옛 글', review1: true, review2: true, note: '메모' })
    expect(withMarksOf(cached, stale)).toMatchObject({ fix: '방금 저장한 글', changed: true, review1: true, review2: true, note: '메모' })
  })
})

describe('rollbackMarks', () => {
  it('restores only the keys this request changed, and only while they still hold its optimistic value', () => {
    const prev = row(1)
    // 1차 요청(낙관적 반영) 뒤에 확인 필요 요청이 성공했다 — 1차가 실패해도 확인 필요는 그대로여야 한다
    const current = { ...applyMarks(prev, { review1: true }), needsCheck: true }
    expect(rollbackMarks(current, prev, { review1: true })).toMatchObject({ review1: false, needsCheck: true })
    // 그 사이 다른 요청이 1차를 다시 바꿨으면 되돌리지 않는다
    const changedAgain = { ...current, review1: false, review2: false }
    expect(rollbackMarks(changedAgain, prev, { review1: true })).toBe(changedAgain)
    expect(rollbackMarks({ ...prev, note: '새 메모' }, { ...prev, note: '옛 메모' }, { note: '새 메모' }).note).toBe('옛 메모')
  })
})
