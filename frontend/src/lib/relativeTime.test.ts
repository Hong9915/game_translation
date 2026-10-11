import { describe, expect, it } from 'vitest'
import { relativeTime } from './relativeTime'

const now = new Date('2026-09-30T12:00:00+09:00')

describe('relativeTime', () => {
  it('1분 미만은 방금', () => expect(relativeTime('2026-09-30T11:59:30+09:00', now)).toBe('방금'))
  it('1시간 미만은 n분 전', () => expect(relativeTime('2026-09-30T11:37:00+09:00', now)).toBe('23분 전'))
  it('하루 미만은 n시간 전', () => expect(relativeTime('2026-09-30T05:10:00+09:00', now)).toBe('6시간 전'))
  it('그보다 오래면 월/일 시:분', () => expect(relativeTime('2026-09-28T09:05:00+09:00', now)).toBe('9/28 09:05'))
  it('미래 시각(시계 어긋남)도 방금', () => expect(relativeTime('2026-09-30T12:00:40+09:00', now)).toBe('방금'))
})
