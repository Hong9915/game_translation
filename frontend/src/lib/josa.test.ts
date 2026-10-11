import { describe, expect, it } from 'vitest'
import { josaLabel } from './josa'

describe('josaLabel', () => {
  it('shows the word for hangul hints', () => {
    expect(josaLabel('아군의 생명를 회복', { at: 4, from: '생명를', to: '생명을', kind: 'HANGUL' })).toBe('생명를 → 생명을')
  })
  it('adds the visible character before particles after numbers and tags', () => {
    const fix = '횟수가 <color=1>3</color>로 증가'
    expect(josaLabel(fix, { at: fix.indexOf('로'), from: '로', to: '으로', kind: 'NUMBER' })).toBe('3로 → 3으로')
  })
})
