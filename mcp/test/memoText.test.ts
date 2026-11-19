import { describe, it, expect } from 'vitest'
import { memoToText } from '../src/memoText.js'

const t = (text: string) => ({ type: 'text', text })
const p = (text: string) => ({ type: 'paragraph', content: [t(text)] })

describe('memoToText', () => {
  it('memoTextConvertsHeadingsListsTasksQuote', () => {
    const doc = {
      type: 'doc',
      content: [
        { type: 'heading', attrs: { level: 2 }, content: [t('규칙')] },
        { type: 'bulletList', content: [{ type: 'listItem', content: [p('존댓말')] }] },
        { type: 'taskList', content: [{ type: 'taskItem', attrs: { checked: true }, content: [p('확인')] }] },
        { type: 'blockquote', content: [p('인용')] },
      ],
    }
    expect(memoToText(JSON.stringify(doc))).toBe('## 규칙\n\n- 존댓말\n\n- [x] 확인\n\n> 인용')
  })

  it('memoTextHandlesEmptyAndBrokenJson', () => {
    expect(memoToText('')).toBe('')
    expect(memoToText('{깨짐')).toBe('{깨짐')
  })

  it('memoTextNonObjectJsonFallsBackToRaw', () => {
    expect(memoToText('null')).toBe('null')
    expect(memoToText('123')).toBe('123')
    expect(memoToText('"abc"')).toBe('"abc"')
  })

  it('memoTextUnknownNodeKeepsMixedText', () => {
    const doc = {
      type: 'doc',
      content: [
        {
          type: 'custom',
          content: [
            { type: 'text', text: '앞' },
            { type: 'paragraph', content: [{ type: 'text', text: '블록' }] },
            { type: 'text', text: '뒤' },
          ],
        },
      ],
    }
    expect(memoToText(JSON.stringify(doc))).toBe('앞\n\n블록\n\n뒤')
  })

  it('memoTextTruncates', () => {
    const doc = { type: 'doc', content: [p('가'.repeat(30000))] }
    const out = memoToText(JSON.stringify(doc))
    expect(out.length).toBeLessThanOrEqual(20000 + 30)
    expect(out.endsWith('(잘림: 전체 30000자)')).toBe(true)
  })

  it('numbersOrderedListAndBreaks', () => {
    const doc = {
      type: 'doc',
      content: [
        { type: 'orderedList', content: [
          { type: 'listItem', content: [p('a')] },
          { type: 'listItem', content: [p('b')] },
        ] },
        { type: 'paragraph', content: [t('x'), { type: 'hardBreak' }, t('y')] },
        { type: 'codeBlock', content: [t('c  d')] },
      ],
    }
    expect(memoToText(JSON.stringify(doc))).toBe('1. a\n2. b\n\nx\ny\n\nc  d')
  })
})
