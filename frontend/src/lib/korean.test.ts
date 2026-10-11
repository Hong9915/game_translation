import { describe, expect, it } from 'vitest'
import { replacePartial, ro } from './korean'

describe('replacePartial', () => {
  it('옛 표기를 맞출 표기로 바꾼다', () => {
    expect(replacePartial('하루에 한 번', '하루에', '매일')).toBe('매일 한 번')
  })
  it('맞출 표기가 옛 표기를 품으면 이미 맞춘 자리는 늘리지 않는다 — 서버 GlossaryApplier.replacePartial과 같다', () => {
    expect(replacePartial('트랩 피해', '트랩', '트랩 장치')).toBe('트랩 장치 피해')
    expect(replacePartial('트랩 장치 피해', '트랩', '트랩 장치')).toBe('트랩 장치 피해')
    expect(replacePartial('트랩 장치와 트랩', '트랩', '트랩 장치')).toBe('트랩 장치와 트랩 장치')
  })
})

describe('ro', () => {
  it('받침으로 로/으로를 고른다', () => {
    expect(ro('매일')).toBe('로')
    expect(ro('랜덤')).toBe('으로')
    expect(ro('하의')).toBe('로')
    expect(ro('트랩 ')).toBe('으로')
    expect(ro('HP')).toBe('로')
  })
})
