interface Node {
  type?: string
  text?: string
  attrs?: { level?: number; checked?: boolean }
  content?: Node[]
}

function inline(n: Node): string {
  if (n.type === 'text') return n.text ?? ''
  if (n.type === 'hardBreak') return '\n'
  return (n.content ?? []).map(inline).join('')
}

function prefixLines(text: string, first: string, rest: string): string {
  return text
    .split('\n')
    .map((l, i) => (i === 0 ? first : rest) + l)
    .join('\n')
}

/** 블록 하나를 글로. 블록끼리는 호출한 쪽이 빈 줄로 잇는다. */
function block(n: Node): string {
  const kids = n.content ?? []
  switch (n.type) {
    case 'heading':
      return '#'.repeat(Math.min(Math.max(n.attrs?.level ?? 1, 1), 6)) + ' ' + inline(n)
    case 'paragraph':
      return inline(n)
    case 'codeBlock':
      return inline(n)
    case 'horizontalRule':
      return '---'
    case 'blockquote':
      return prefixLines(blocks(kids), '> ', '> ')
    case 'bulletList':
      return kids.map((li) => prefixLines(blocks(li.content ?? []), '- ', '  ')).join('\n')
    case 'orderedList':
      return kids.map((li, i) => prefixLines(blocks(li.content ?? []), `${i + 1}. `, '   ')).join('\n')
    case 'taskList':
      return kids
        .map((li) => prefixLines(blocks(li.content ?? []), li.attrs?.checked ? '- [x] ' : '- [ ] ', '  '))
        .join('\n')
    default: {
      if (kids.length === 0) return ''
      const isInline = (k: Node) => k.type === 'text' || k.type === 'hardBreak'
      if (kids.every(isInline)) return inline(n)
      // 글자와 블록이 섞이면 글자를 잃지 않게 이어 붙인다.
      const parts: string[] = []
      let run: Node[] = []
      const flush = () => {
        if (run.length > 0) parts.push(run.map(inline).join(''))
        run = []
      }
      for (const k of kids) {
        if (isInline(k)) run.push(k)
        else {
          flush()
          parts.push(block(k))
        }
      }
      flush()
      return parts.join('\n\n')
    }
  }
}

function blocks(nodes: Node[]): string {
  return nodes.map(block).join('\n\n')
}

function cut(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max)}\n(잘림: 전체 ${s.length}자)` : s
}

/** 메모(Tiptap JSON 문자열)를 Claude가 읽을 글로 바꾼다. */
export function memoToText(content: string, max = 20000): string {
  if (!content) return ''
  let doc: Node
  try {
    const parsed: unknown = JSON.parse(content)
    if (typeof parsed !== 'object' || parsed === null) return cut(content, max)
    doc = parsed as Node
  } catch {
    return cut(content, max)
  }
  return cut(blocks(doc.content ?? []), max)
}
