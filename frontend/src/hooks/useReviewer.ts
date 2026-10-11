import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { api } from '../api/client'
import type { ReviewerRef } from '../api/types'
import { loadReviewer, storeReviewer } from '../lib/reviewer'

export function useReviewer() {
  const qc = useQueryClient()
  const [reviewer, setReviewer] = useState<ReviewerRef | null>(() => loadReviewer())
  const setName = useCallback(async (name: string) => {
    const r = await api.reviewers.upsert(name)
    storeReviewer(r)
    setReviewer(r)
    qc.invalidateQueries({ queryKey: ['reviewers'] })
    return r
  }, [qc])
  // 서버가 이미 돌려준 결과(예: 내 이름을 PATCH로 바꾼 응답)를 그대로 저장한다 — upsert처럼 새로 요청하지 않는다.
  const setStored = useCallback((r: ReviewerRef) => { storeReviewer(r); setReviewer(r) }, [])
  const clear = useCallback(() => { storeReviewer(null); setReviewer(null) }, [])
  return { reviewer, setName, setStored, clear }
}
