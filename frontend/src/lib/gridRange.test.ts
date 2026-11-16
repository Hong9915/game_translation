import { describe, it, expect } from 'vitest'
import { columnClipboard, inRange, rangeBounds } from './gridRange'

describe('gridRange', () => {
  it('rangeBounds는 작은 쪽, 큰 쪽 순서', () => {
    expect(rangeBounds({ col: 'fix', anchor: 5, head: 2 })).toEqual([2, 5])
    expect(rangeBounds({ col: 'src', anchor: 1, head: 3 })).toEqual([1, 3])
  })

  it('inRange는 같은 열의 범위 안만 true', () => {
    const r = { col: 'fix' as const, anchor: 5, head: 2 }
    expect(inRange(r, 2, 'fix')).toBe(true)
    expect(inRange(r, 5, 'fix')).toBe(true)
    expect(inRange(r, 6, 'fix')).toBe(false)
    expect(inRange(r, 3, 'src')).toBe(false)
    expect(inRange(null, 3, 'fix')).toBe(false)
  })

  it('quotes cells with newline tab or quote', () => {
    expect(columnClipboard(['가', 'a\nb', 'x"y', null, 't\tz'])).toBe('가\n"a\nb"\n"x""y"\n\n"t\tz"')
  })

  it('\\r\\n은 \\n으로, 끝 줄바꿈은 없다', () => {
    expect(columnClipboard(['a\r\nb', undefined])).toBe('"a\nb"\n')
    expect(columnClipboard(['a', 'b'])).toBe('a\nb')
    expect(columnClipboard([])).toBe('')
  })
})
