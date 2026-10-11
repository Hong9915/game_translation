import { describe, expect, it } from 'vitest'
import { textDiff } from './textDiff'

describe('textDiff', () => {
  it('앞 판과 같은 글자·새 글자·지운 글자로 나눈다', () => {
    expect(textDiff('정의의 심판', '정의의 재판')).toEqual([
      { kind: 'same', text: '정의의 ' }, { kind: 'removed', text: '심' }, { kind: 'added', text: '재' }, { kind: 'same', text: '판' },
    ])
  })

  it('앞 판이 없으면 전부 같은 글자로 둔다', () => {
    expect(textDiff(null, '정의의 심판')).toEqual([{ kind: 'same', text: '정의의 심판' }])
  })

  it('띄어쓰기만 더한 것도 잡는다', () => {
    expect(textDiff('적에게<b>화염</b>피해', '적에게 <b>화염</b> 피해').filter((p) => p.kind === 'added')).toEqual([
      { kind: 'added', text: ' ' }, { kind: 'added', text: ' ' },
    ])
  })

  it('아주 긴 글은 비교하지 않고 그대로 둔다', () => {
    const long = '가'.repeat(2000)
    expect(textDiff(long, long + '나')).toEqual([{ kind: 'same', text: long + '나' }])
  })
})
