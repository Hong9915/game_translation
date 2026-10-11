import type { GlossaryVariant } from '../api/types'

/** 띄어쓰기 한꺼번에 통일에서 맞출 쪽. frequent = 게임에서 많이 쓴 꼴, standard = Kiwi 표준 띄어쓰기(없으면 많이 쓴 꼴). */
export type SpacingPolicy = 'frequent' | 'standard'

const squash = (s: string) => s.replace(/\s+/g, '')

/**
 * 띄어쓰기 카드 하나에서 맞출 꼴(ko), 바꿀 꼴(replace), 바뀔 대략의 행 수(rows = 바꿀 꼴들의 행 수 합).
 * 주 표기와 공백만 다른 꼴만 본다 — 다른 낱말 표기는 카드마다 통일 창에서 사람이 고른다.
 */
export function spacingTarget(v: GlossaryVariant, policy: SpacingPolicy): { ko: string; replace: string[]; rows: number } {
  const forms = [v.main, ...v.alts.filter((a) => squash(a.ko) === squash(v.main.ko))]
  const frequent = forms.reduce((best, f) => (f.rows > best.rows ? f : best), forms[0])
  const ko = policy === 'standard' && v.standard && forms.some((f) => f.ko === v.standard) ? v.standard : frequent.ko
  const others = forms.filter((f) => f.ko !== ko)
  return { ko, replace: others.map((f) => f.ko), rows: others.reduce((n, f) => n + f.rows, 0) }
}

export interface TagSpacingPart { kind: 'text' | 'tag' | 'added'; text: string }

/**
 * 태그 경계 띄어쓰기 후보(after)를 보여 줄 조각으로 나눈다: 태그, before에 없던 새 공백, 나머지 글자.
 * after는 before에 공백만 더한 꼴이다(서버가 그것만 돌려준다).
 */
export function tagSpacingParts(before: string, after: string): TagSpacingPart[] {
  const added = new Set<number>()
  for (let i = 0, j = 0; j < after.length; j++) {
    if (i < before.length && before[i] === after[j]) i++
    else added.add(j)
  }
  const parts: TagSpacingPart[] = []
  const push = (kind: TagSpacingPart['kind'], text: string) => {
    const last = parts[parts.length - 1]
    if (last && last.kind === kind && kind !== 'tag') last.text += text
    else parts.push({ kind, text })
  }
  const tag = /<[^>]+>/g
  let at = 0
  for (const m of [...after.matchAll(tag), null]) {
    const end = m ? m.index : after.length
    for (let k = at; k < end; k++) push(added.has(k) ? 'added' : 'text', after[k])
    if (m) { push('tag', m[0]); at = end + m[0].length }
  }
  return parts
}
