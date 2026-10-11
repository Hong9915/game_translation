import type { ReviewerRef } from '../api/types'

const KEY = 'gametrans.reviewer'

/** 이 브라우저에 저장해 둔 검토자. 로그인이 없으므로 이것이 곧 "나"다. */
export function loadReviewer(): ReviewerRef | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const r = JSON.parse(raw)
    return r && typeof r.id === 'number' && typeof r.name === 'string' ? (r as ReviewerRef) : null
  } catch {
    return null
  }
}

export function storeReviewer(r: ReviewerRef | null): void {
  try {
    if (r) localStorage.setItem(KEY, JSON.stringify(r))
    else localStorage.removeItem(KEY)
  } catch { /* 저장이 막힌 브라우저에서도 화면은 동작해야 한다 */ }
}
