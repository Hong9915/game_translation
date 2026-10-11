import { describe, expect, it } from 'vitest'
import type { GlossaryVariant } from '../api/types'
import { spacingTarget, tagSpacingParts } from './spacing'

const r = (ko: string, rows: number, spacing = false) => ({ ko, rows, spacing, examples: [] })
const v = (main: [string, number], alts: [string, number][], standard: string | null = null): GlossaryVariant => ({
  find: '野猪王', kind: 'SPACING', rows: 0, missingRows: 0, missing: [], standard,
  main: r(...main), alts: alts.map(([ko, n]) => r(ko, n, true)),
})

describe('spacingTarget', () => {
  it('많이 쓴 꼴로 맞추고 나머지 꼴을 바꿀 꼴로 둔다', () => {
    expect(spacingTarget(v(['멧돼지왕', 21], [['멧돼지 왕', 11]]), 'frequent')).toEqual({ ko: '멧돼지왕', replace: ['멧돼지 왕'], rows: 11 })
  })

  it('띄어쓰기 꼴이 더 많이 쓰였으면 그쪽이 많이 쓴 꼴이다', () => {
    expect(spacingTarget(v(['보물상자', 3], [['보물 상자', 5]]), 'frequent')).toEqual({ ko: '보물 상자', replace: ['보물상자'], rows: 3 })
  })

  it('표준 띄어쓰기를 고르면 Kiwi 꼴로, 없으면 많이 쓴 꼴로 맞춘다', () => {
    expect(spacingTarget(v(['멧돼지왕', 21], [['멧돼지 왕', 11]], '멧돼지 왕'), 'standard')).toEqual({ ko: '멧돼지 왕', replace: ['멧돼지왕'], rows: 21 })
    expect(spacingTarget(v(['일일', 74], [['일 일', 1]]), 'standard')).toEqual({ ko: '일일', replace: ['일 일'], rows: 1 })
  })

  it('다른 낱말 표기는 띄어쓰기 통일에 넣지 않는다', () => {
    const x = v(['멧돼지왕', 21], [['멧돼지 왕', 11]])
    x.alts.push(r('보스 돼지', 4))
    expect(spacingTarget(x, 'frequent')).toEqual({ ko: '멧돼지왕', replace: ['멧돼지 왕'], rows: 11 })
  })
})

describe('tagSpacingParts', () => {
  it('태그·새로 넣은 공백·글자로 나눈다', () => {
    expect(tagSpacingParts('적에게<color=red>화염</color>피해', '적에게 <color=red>화염</color> 피해')).toEqual([
      { kind: 'text', text: '적에게' }, { kind: 'added', text: ' ' }, { kind: 'tag', text: '<color=red>' }, { kind: 'text', text: '화염' },
      { kind: 'tag', text: '</color>' }, { kind: 'added', text: ' ' }, { kind: 'text', text: '피해' },
    ])
  })

  it('원래 있던 공백은 글자로 둔다', () => {
    expect(tagSpacingParts('공격 <b>1</b>번', '공격 <b>1</b>번').map((p) => p.kind)).toEqual(['text', 'tag', 'text', 'tag', 'text'])
  })
})
