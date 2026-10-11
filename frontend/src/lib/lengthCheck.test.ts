import { describe, expect, it } from 'vitest'
import { lengthCount, lengthFlagged } from './lengthCheck'

// 백엔드 LengthCheck와 같은 규칙: 자리표시자({0}, %d, <태그>)와 공백을 뺀 코드포인트 수
describe('lengthCheck', () => {
  it('counts code points without placeholders, tags and whitespace', () => {
    expect(lengthCount('使用后恢复{0}点生命值')).toBe(9)
    expect(lengthCount('<color=#FFD700>传说</color> 品质装备')).toBe(6)
    expect(lengthCount('HP %d 회복')).toBe(4)
    expect(lengthCount(null)).toBe(0)
    expect(lengthCount('')).toBe(0)
  })

  it('flags when korean exceeds source × ratio, never when source is empty', () => {
    expect(lengthFlagged('确认', '확인', 1)).toBe(false)
    expect(lengthFlagged('确认', '확인합니다', 1)).toBe(true)
    expect(lengthFlagged('确认', '확인합니다', 3)).toBe(false)
    expect(lengthFlagged('{0}', '아주 긴 문장', 1)).toBe(false)
  })
})
