import type { JosaHint } from '../api/types'

const TAG = /<[^>]+>/g
/** 검사 대기 행이 있는 동안 요약을 다시 읽는 간격. 남은 행 수와 조사 확인 건수가 새로 고침 없이 바뀐다. */
const JOSA_POLL_MS = 15_000

export function josaRefetchInterval(summary: { josaPending: number } | undefined): number | false {
  return summary && summary.josaPending > 0 ? JOSA_POLL_MS : false
}

/** 제안 표시 문구. 숫자·태그 뒤는 조사만 오므로 태그를 걷은 앞 글자 하나를 붙인다(3로 → 3으로). 백엔드 JosaHints.label과 같다. */
export function josaLabel(fix: string, h: JosaHint): string {
  if (h.kind === 'HANGUL') return `${h.from} → ${h.to}`
  const visible = fix.slice(0, Math.max(0, h.at)).replace(TAG, '')
  const ctx = visible.slice(-1)
  return `${ctx}${h.from} → ${ctx}${h.to}`
}
