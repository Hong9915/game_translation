import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { PresenceEntry, ReviewEvent, ReviewerRef, WorkspaceRowsResponse } from '../api/types'
import { isProjectStreamOpen, subscribeProjectStream } from '../lib/projectStream'
import { patchRows } from '../lib/workspaceCache'

const HIGHLIGHT_MS = 2000
const SUMMARY_COALESCE_MS = 500

/** 프로젝트 SSE 스트림의 연결 상태. */
export type ConnStatus = 'connecting' | 'open' | 'lost'

/**
 * 검수 워크스페이스의 실시간 반영. useReviewEvents와 같은 스트림을 쓰지만 새 목록 캐시(['workspace-rows', …])를 패치한다.
 * 편집 중인 글은 그리드 상태에 있으므로 이벤트가 와도 사라지지 않는다.
 * onRowUpdated: segment-updated로 어떤 행의 fix가 바뀔 때마다 부른다(누가 바꿨든) — 저장 실패로 남겨둔 텍스트가
 * 그 사이 바뀐 서버 값을 가리게 두지 않도록 부모(WorkspacePage)가 failedText를 정리하는 데 쓴다.
 */
export function useWorkspaceEvents(projectId: number, me: ReviewerRef | null, onRowUpdated?: (id: number, fix: string | null) => void,
  /** 목록·요약·변경 내역 캐시 키. 파일 화면은 프로젝트 id, 게임 화면은 'g{게임 id}'(스트림은 -게임 id). */
  cacheKey: number | string = projectId) {
  const qc = useQueryClient()
  const [highlighted, setHighlighted] = useState<Set<number>>(new Set())
  const [presence, setPresenceState] = useState<PresenceEntry[]>([])
  const [status, setStatus] = useState<ConnStatus>(() => (isProjectStreamOpen(projectId) ? 'open' : 'connecting'))
  const meId = useRef<number | null>(me?.id ?? null)
  useEffect(() => { meId.current = me?.id ?? null }, [me])
  const onRowUpdatedRef = useRef(onRowUpdated)
  useEffect(() => { onRowUpdatedRef.current = onRowUpdated }, [onRowUpdated])
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])
  const summaryTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const setPresence = (list: PresenceEntry[]) => setPresenceState(list.filter((p) => p.id !== meId.current))

  useEffect(() => {
    let seenOpen = false, sawError = false
    let alreadyOpen = false
    const invalidateAll = () => {
      qc.invalidateQueries({ queryKey: ['workspace-rows', cacheKey] })
      qc.invalidateQueries({ queryKey: ['workspace-summary', cacheKey] })
    }
    // 게임 화면은 일괄 체크·용어 다시 계산 한 번에 파일마다 marks-bulk/refresh가 온다(캐슬케이퍼 41개).
    // 받을 때마다 다시 읽으면 수십 벌의 목록·요약 요청이 서버에 쌓이므로 500ms 동안 모아 한 번만 읽는다.
    // 첫 이벤트에서 시작한 타이머를 뒤 이벤트가 늦추지 않는다 — 계속 와도 500ms마다는 반영된다.
    let reloadTimer: ReturnType<typeof setTimeout> | null = null
    let reloadReviewers = false
    const scheduleReload = (withReviewers = false) => {
      reloadReviewers ||= withReviewers
      if (reloadTimer) return
      reloadTimer = setTimeout(() => {
        reloadTimer = null
        invalidateAll()
        if (reloadReviewers) qc.invalidateQueries({ queryKey: ['reviewers'] })
        reloadReviewers = false
      }, SUMMARY_COALESCE_MS)
    }
    const flash = (ids: number[]) => {
      setHighlighted((prev) => new Set([...prev, ...ids]))
      const h = setTimeout(() => {
        setHighlighted((prev) => { const next = new Set(prev); ids.forEach((i) => next.delete(i)); return next })
        timers.current = timers.current.filter((t) => t !== h)
      }, HIGHLIGHT_MS)
      timers.current.push(h)
    }
    const scheduleSummary = () => {
      if (summaryTimer.current) clearTimeout(summaryTimer.current)
      summaryTimer.current = setTimeout(() => { summaryTimer.current = null; qc.invalidateQueries({ queryKey: ['workspace-summary', cacheKey] }) }, SUMMARY_COALESCE_MS)
    }
    // 변경 내역 패널(설계 2026-09-30 §4.4): 새 이벤트 없이 기존 세 이벤트를 받으면 피드 상단을 다시 읽는다.
    // 요약과 같은 500ms 병합. 패널이 닫혀 있으면 구독자가 없어 아무 요청도 안 나간다.
    let activityTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleActivity = () => {
      if (activityTimer) clearTimeout(activityTimer)
      activityTimer = setTimeout(() => { activityTimer = null; qc.invalidateQueries({ queryKey: ['activity', cacheKey] }) }, SUMMARY_COALESCE_MS)
    }
    const off = subscribeProjectStream(projectId, {
      open: () => { setStatus('open'); if (alreadyOpen || seenOpen || sawError) invalidateAll(); seenOpen = true },
      error: () => { sawError = true; setStatus('lost') },
      review: (e) => {
        const ev = JSON.parse((e as MessageEvent).data) as ReviewEvent
        if (ev.type === 'presence') { setPresence(ev.reviewers); return }
        if (ev.type === 'refresh') { scheduleReload(true); scheduleActivity(); return }
        // 누가 단어장 항목을 고쳤다: 열린 단어장 패널이 목록을 다시 읽는다(행이 안 바뀌었어도 온다).
        if (ev.type === 'glossary') { qc.invalidateQueries({ queryKey: ['glossary'] }); scheduleActivity(); return }
        // 일괄 체크가 커서 행 목록 없이 왔다: 목록·요약을 다시 읽는다
        if (ev.type === 'marks-bulk') { scheduleReload(); return }
        for (const [key, data] of qc.getQueriesData<WorkspaceRowsResponse>({ queryKey: ['workspace-rows', cacheKey] })) {
          if (data) qc.setQueryData(key, patchRows(data, ev))
        }
        if (ev.type === 'segment-updated') onRowUpdatedRef.current?.(ev.row.id, ev.row.currentText)
        if (ev.type === 'segment-updated') scheduleActivity()
        scheduleSummary()
        const mine = meId.current != null && ev.by === meId.current
        if (!mine) flash(ev.type === 'segment-updated' ? [ev.row.id] : ev.segmentIds)
      },
    })
    alreadyOpen = isProjectStreamOpen(projectId)
    return () => {
      off()
      timers.current.forEach(clearTimeout); timers.current = []
      if (summaryTimer.current) { clearTimeout(summaryTimer.current); summaryTimer.current = null }
      if (activityTimer) { clearTimeout(activityTimer); activityTimer = null }
      if (reloadTimer) { clearTimeout(reloadTimer); reloadTimer = null }
    }
  }, [projectId, cacheKey, qc])

  return { highlighted, presence, setPresence, status }
}
