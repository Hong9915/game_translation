import { useEffect, useState } from 'react'

/**
 * 오른쪽 패널처럼 닫힐 때 폭 전환이 끝날 때까지 남아 있어야 하는 것의 "지금 그릴 값".
 * value가 null이 되어도 ms 동안은 마지막 값을 돌려준다(shown).
 * seq는 닫혔다가 다시 열릴 때마다 늘어난다 — key로 써서, 아직 남아 있던 패널을 새로 마운트한다(옛 폼 값이 남지 않게).
 */
export function useLinger<T>(value: T | null, ms: number): { shown: T | null; seq: number } {
  const [state, setState] = useState({ shown: value, seq: 0, prev: value })
  // 렌더 단계 보정 — effect로 하면 한 렌더 동안 옛 값이 보인다
  if (state.prev !== value) {
    setState((s) => ({ prev: value, shown: value ?? s.shown, seq: s.prev === null && value !== null ? s.seq + 1 : s.seq }))
  }
  useEffect(() => {
    if (value !== null) return
    const t = setTimeout(() => setState((s) => (s.prev === null ? { ...s, shown: null } : s)), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return { shown: state.shown, seq: state.seq }
}
