import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { api } from '../api/client'
import type { ProposalEvent, ProposalSummary, ReviewerRef } from '../api/types'
import { subscribeProjectStream } from '../lib/projectStream'

/**
 * 내 Claude 제안(PENDING) 목록과 지금 띄울 제안. 목록은 직접 읽고(실패는 삼킨다), 스트림의 connector-proposal 이벤트가
 * 내 검수자·이 게임 것일 때만 다시 읽는다. "나중에"는 이 탭 메모리에서만 미룬다(서버를 안 부름).
 * stream: 파일 화면은 프로젝트 id, 게임 화면은 -게임 id.
 */
const EMPTY: ProposalSummary[] = []
/** "나중에"로 미룬 제안 id — 이 페이지 로드 동안, 검수자별. 화면·파일을 옮겨도 남는다(새로고침·새 탭이면 사라진다). */
const deferredByReviewer = new Map<number, Set<number>>()
const NO_DEFERRED: ReadonlySet<number> = new Set()
export function resetDeferredForTests() { deferredByReviewer.clear() }

export function useConnectorProposals(stream: number, gameId: number | null, me: ReviewerRef | null) {
  const meId = me?.id ?? null
  const [stored, setStored] = useState<ProposalSummary[]>([])
  // stored가 누구 것인지. 검수자를 바꾼 첫 렌더에는 아직 옛 목록이 들어 있으므로(리셋은 effect), 다른 사람 것은 내보내지 않는다.
  const [owner, setOwner] = useState<number | null>(meId)
  const pending = owner === meId ? stored : EMPTY
  const setPending = setStored
  const [, bump] = useReducer((n: number) => n + 1, 0)
  const deferred = (meId != null ? deferredByReviewer.get(meId) : undefined) ?? NO_DEFERRED
  const [shown, setShown] = useState<number | null>(null)
  const gen = useRef(0)

  const load = useCallback(() => {
    if (gameId == null || meId == null) return
    const mine = ++gen.current
    api.proposals.list(gameId, meId).then((list) => {
      if (gen.current === mine) setPending(list)   // 그사이 me·gameId가 바뀌었거나 더 새 요청이 있으면 버린다
    }, () => { /* 목록을 못 읽어도 화면에 오류를 띄우지 않는다 */ })
  }, [gameId, meId])

  useEffect(() => {
    gen.current++
    setPending([]); setOwner(meId); setShown(null)
    if (gameId == null || meId == null) return
    load()
    const off = subscribeProjectStream(stream, {
      // 끊겼다 다시 이어지면 그 사이 온 제안을 놓쳤을 수 있으니 목록을 다시 읽는다
      open: load,
      proposal: (e) => {
        let ev: ProposalEvent
        try { ev = JSON.parse((e as MessageEvent).data) as ProposalEvent } catch { return }
        if (ev.type !== 'connector-proposal' && ev.type !== 'connector-proposal-decided') return
        if (ev.reviewerId !== meId || ev.gameId !== gameId) return
        if (ev.type === 'connector-proposal') load()
        else setPending((p) => p.filter((x) => x.id !== ev.proposalId))
      },
    })
    return () => { off(); gen.current++ }
  }, [stream, gameId, meId, load])

  // 보여 주는 중인 것이 아직 pending에 있으면 그대로, 아니면 미루지 않은 첫 번째
  const current = shown != null && pending.some((p) => p.id === shown)
    ? shown
    : pending.find((p) => !deferred.has(p.id))?.id ?? null
  useEffect(() => { if (shown !== current) setShown(current) }, [shown, current])

  const idx = current == null ? -1 : pending.findIndex((p) => p.id === current)
  const position: [number, number] | null = idx < 0 ? null : [idx + 1, pending.length]

  const later = useCallback(() => {
    if (current == null) return
    if (meId != null) { deferredByReviewer.set(meId, new Set(deferredByReviewer.get(meId)).add(current)); bump() }
    setShown(null)   // 미룬 것은 pending에 남아 있어도 그대로 보여 주지 않는다
  }, [current, meId])
  const openAll = useCallback(() => {
    if (meId != null) deferredByReviewer.delete(meId)
    setShown(pending[0]?.id ?? null)
  }, [pending, meId])
  const decided = useCallback((id: number) => setPending((p) => p.filter((x) => x.id !== id)), [])

  return { pending, current, position, later, openAll, decided }
}
