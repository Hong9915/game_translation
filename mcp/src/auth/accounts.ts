import type { AccountInfo, BackendClient } from '../backend.js'

export interface AccountChecker {
  /** 쓸 수 있는 계정이면 계정 정보, 막혔거나 검수자가 없으면 null. 백엔드 오류는 그대로 던진다. */
  active(accountId: number): Promise<AccountInfo | null>
}

/** 계정 막힘은 최대 ttlMs(기본 60초) 늦게 반영된다. 백엔드 오류는 캐시하지 않는다. */
export function createAccountChecker(
  backend: BackendClient,
  ttlMs = 60_000,
  now: () => number = Date.now,
): AccountChecker {
  const cache = new Map<number, { at: number; info: AccountInfo | null }>()
  return {
    async active(accountId) {
      const hit = cache.get(accountId)
      if (hit && now() - hit.at < ttlMs) return hit.info
      const info = await backend.account(accountId)
      cache.set(accountId, { at: now(), info })
      return info
    },
  }
}
