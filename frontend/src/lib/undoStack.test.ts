import { describe, it, expect } from 'vitest'
import { emptyUndo, insertPast, popFuture, popPast, pushUnit, toFuture, toPast } from './undoStack'

const u = (n: number) => ({ label: `u${n}`, activityIds: [n] })

describe('undoStack', () => {
  it('push → popPast → toFuture → popFuture → toPast', () => {
    let s = pushUnit(emptyUndo, u(1))
    expect(s.past).toEqual([u(1)])
    const [unit, s2] = popPast(s)
    expect(unit).toEqual(u(1))
    expect(s2.past).toEqual([])
    s = toFuture(s2, u(2))
    expect(s.future).toEqual([u(2)])
    const [redo, s3] = popFuture(s)
    expect(redo).toEqual(u(2))
    expect(s3.future).toEqual([])
    expect(toPast(s3, u(3)).past).toEqual([u(3)])
  })

  it('빈 스택에서 pop은 null', () => {
    expect(popPast(emptyUndo)[0]).toBeNull()
    expect(popFuture(emptyUndo)[0]).toBeNull()
  })

  it('pushUnit은 future를 비우고 빈 ids는 무시', () => {
    const s = toFuture(emptyUndo, u(9))
    expect(pushUnit(s, u(1)).future).toEqual([])
    expect(pushUnit(s, { label: 'x', activityIds: [] })).toBe(s)
  })

  it('toFuture·toPast는 반대쪽을 비우지 않고 빈 ids는 무시', () => {
    const s = { past: [u(1)], future: [u(2)] }
    expect(toFuture(s, u(3))).toEqual({ past: [u(1)], future: [u(2), u(3)] })
    expect(toPast(s, u(3))).toEqual({ past: [u(1), u(3)], future: [u(2)] })
    expect(toPast(s, { label: 'x', activityIds: [] })).toBe(s)
    expect(toFuture(s, { label: 'x', activityIds: [] })).toBe(s)
  })

  it('51번째에서 가장 오래된 것을 버린다', () => {
    let s = emptyUndo
    for (let i = 1; i <= 51; i++) s = pushUnit(s, u(i))
    expect(s.past).toHaveLength(50)
    expect(s.past[0]).toEqual(u(2))
    expect(s.past[49]).toEqual(u(51))
  })

  it('insertPast puts the unit below newer pushes', () => {
    const s = pushUnit(pushUnit(emptyUndo, { label: 'a', activityIds: [1] }), { label: 'c', activityIds: [3] })
    const out = insertPast(s, { label: 'b', activityIds: [2] }, 1)
    expect(out.past.map((x) => x.label)).toEqual(['a', 'b', 'c'])
    expect(insertPast(s, { label: 'b', activityIds: [2] }, 0).past.map((x) => x.label)).toEqual(['a', 'c', 'b'])
    expect(insertPast(s, { label: 'b', activityIds: [2] }, 9).past[0].label).toBe('b')
    expect(insertPast(s, { label: 'b', activityIds: [] }, 0)).toBe(s)
  })
})
