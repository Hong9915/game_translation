export interface DiffPart { kind: 'same' | 'added' | 'removed'; text: string }

/** 글자 단위 비교를 하지 않는 크기(두 글 길이의 곱). 넘으면 칠하지 않고 그대로 보인다. */
const MAX_CELLS = 1_000_000

/**
 * 변경 내역 한 판을 바로 앞 판과 글자 단위로 비교한다(LCS). 지운 글자는 그 자리에 removed로 끼운다.
 * 앞 판이 없거나 너무 길면 전부 same 하나로 돌려준다.
 */
export function textDiff(prev: string | null, next: string): DiffPart[] {
  if (prev === null || prev === next || prev.length * next.length > MAX_CELLS) return next ? [{ kind: 'same', text: next }] : []
  const a = [...prev], b = [...next]
  const n = a.length, m = b.length
  // lcs[i][j] = a[i..]와 b[j..]의 최장 공통 부분열 길이
  const lcs = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1))
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
  const out: DiffPart[] = []
  const push = (kind: DiffPart['kind'], ch: string) => {
    const last = out[out.length - 1]
    if (last && last.kind === kind) last.text += ch
    else out.push({ kind, text: ch })
  }
  let i = 0, j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) { push('same', a[i]); i++; j++ }
    else if (lcs[i + 1][j] >= lcs[i][j + 1]) push('removed', a[i++])
    else push('added', b[j++])
  }
  while (i < n) push('removed', a[i++])
  while (j < m) push('added', b[j++])
  return out
}
