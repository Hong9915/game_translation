import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect } from 'react'
import { api } from '../api/client'
import type { GlossaryCandidate, GlossaryCandidateEvent } from '../api/types'
import { subscribeProjectStream } from '../lib/projectStream'

const EMPTY: GlossaryCandidate[] = []

/**
 * 이 게임의 승인 대기(PENDING) 단어장 후보. 단어장 패널의 "승인 대기" 칸과 같은 쿼리 키를 쓴다.
 * 스트림의 glossary-candidate 이벤트(이 게임 것)나 재연결 때 다시 읽는다. 읽기 실패는 빈 목록.
 * stream: 파일 화면은 프로젝트 id, 게임 화면은 -게임 id.
 */
export function useGlossaryCandidates(stream: number, gameId: number | null) {
  const qc = useQueryClient()
  const q = useQuery({
    queryKey: ['glossaryCandidates', gameId],
    queryFn: () => api.glossaryCandidates.list(gameId as number).catch(() => EMPTY),
    enabled: gameId != null,
  })
  const reload = useCallback(() => { void qc.invalidateQueries({ queryKey: ['glossaryCandidates', gameId] }) }, [qc, gameId])

  useEffect(() => {
    if (gameId == null) return
    return subscribeProjectStream(stream, {
      open: reload,
      'glossary-candidate': (e) => {
        let ev: GlossaryCandidateEvent
        try { ev = JSON.parse((e as MessageEvent).data) as GlossaryCandidateEvent } catch { return }
        if (ev.gameId === gameId) reload()
      },
    })
  }, [stream, gameId, reload])

  return { list: gameId == null ? EMPTY : q.data ?? EMPTY, reload }
}
