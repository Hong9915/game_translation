import { describe, expect, it } from 'vitest'
import type { ColumnPair } from '../api/types'
import { applyFixMode, applyRole, canOverwrite, defaultFixHeader, roleOptions, roleValue } from './columnRoles'

const pair = (over: Partial<ColumnPair> = {}): ColumnPair =>
  ({ srcCol: 'C', enCol: 'D', koColMode: 'EXISTING_TO_NEW', koCol: 'E', koHeader: 'KR_Fix', ...over })
const cfg = (over: Partial<{ keyCol: string | null; pairs: ColumnPair[] }> = {}) =>
  ({ keyCol: 'A', pairs: [pair()], ...over })

describe('roleValue', () => {
  it('names each column by the slot it fills', () => {
    const c = cfg()
    expect(roleValue(c, 'A')).toBe('ID')
    expect(roleValue(c, 'C')).toBe('SRC:0')
    expect(roleValue(c, 'D')).toBe('EN:0')
    expect(roleValue(c, 'E')).toBe('KO:0')
    expect(roleValue(c, 'B')).toBe('NONE')
  })

  it('numbers the slots of a second pair', () => {
    const c = cfg({ pairs: [pair(), pair({ srcCol: 'F', enCol: null, koCol: 'G', koHeader: 'KR_Fix2' })] })
    expect(roleValue(c, 'F')).toBe('SRC:1')
    expect(roleValue(c, 'G')).toBe('KO:1')
  })
})

describe('roleOptions', () => {
  it('lists plain labels for one pair', () => {
    expect(roleOptions(1).map((o) => o.label)).toEqual(['— 없음 —', 'ID', '간체 원문', '영어', '기존 한국어'])
  })

  it('numbers the labels when there are several pairs', () => {
    expect(roleOptions(2).map((o) => o.label)).toEqual(
      ['— 없음 —', 'ID', '간체 원문 1', '영어 1', '기존 한국어 1', '간체 원문 2', '영어 2', '기존 한국어 2'],
    )
  })
})

describe('applyRole', () => {
  it('moves a role to another column, clearing the old holder', () => {
    const next = applyRole(cfg(), 'B', 'SRC:0', 'NEW')
    expect(next.pairs[0].srcCol).toBe('B')
    expect(roleValue(next, 'C')).toBe('NONE')
  })

  it('clears a slot when set to none', () => {
    const next = applyRole(cfg(), 'D', 'NONE', 'NEW')
    expect(next.pairs[0].enCol).toBeNull()
    const cleared = applyRole(cfg(), 'A', 'NONE', 'NEW')
    expect(cleared.keyCol).toBeNull()
  })

  it('setting a korean column picks the mode from the fix mode', () => {
    const toNew = applyRole(cfg({ pairs: [pair({ koCol: null, koColMode: 'NEW' })] }), 'E', 'KO:0', 'NEW')
    expect(toNew.pairs[0]).toMatchObject({ koCol: 'E', koColMode: 'EXISTING_TO_NEW', koHeader: 'KR_Fix' })
    const over = applyRole(cfg({ pairs: [pair({ koCol: null, koColMode: 'NEW' })] }), 'E', 'KO:0', 'OVERWRITE')
    expect(over.pairs[0]).toMatchObject({ koCol: 'E', koColMode: 'EXISTING' })
  })

  it('dropping the korean column falls back to a new column', () => {
    const next = applyRole(cfg(), 'E', 'NONE', 'NEW')
    expect(next.pairs[0]).toMatchObject({ koCol: null, koColMode: 'NEW', koHeader: 'KR_Fix' })
  })
})

describe('applyFixMode', () => {
  it('switches every pair that reads an existing column', () => {
    const c = cfg({ pairs: [pair(), pair({ srcCol: 'F', koCol: null, koColMode: 'NEW', koHeader: 'KR_Fix2' })] })
    const over = applyFixMode(c, 'OVERWRITE')
    expect(over.pairs[0].koColMode).toBe('EXISTING')
    // 읽을 기존 열이 없는 쌍은 덮어쓸 곳도 없으므로 새 열 그대로
    expect(over.pairs[1].koColMode).toBe('NEW')
    expect(applyFixMode(over, 'NEW').pairs[0].koColMode).toBe('EXISTING_TO_NEW')
  })
})

describe('canOverwrite / defaultFixHeader', () => {
  it('overwrite needs at least one existing korean column', () => {
    expect(canOverwrite(cfg())).toBe(true)
    expect(canOverwrite(cfg({ pairs: [pair({ koCol: null, koColMode: 'NEW' })] }))).toBe(false)
  })

  it('numbers extra fix headers', () => {
    expect(defaultFixHeader(0)).toBe('KR_Fix')
    expect(defaultFixHeader(1)).toBe('KR_Fix2')
  })
})
