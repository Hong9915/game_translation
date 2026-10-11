import { useEffect, useRef } from 'react'
import { api } from '../api/client'
import type { PresenceEntry, ReviewerRef } from '../api/types'

const HEARTBEAT_MS = 25_000
// blur→focus처럼 짧게 두 번 바뀌는 셀 전환에서 POST가 두 번 나가지 않도록, 마지막 값 하나만 이 시간 뒤에 보낸다.
const CHANGE_DEBOUNCE_MS = 300

/**
 * 내가 이 검토 화면에 있고 어느 시트·어느 셀을 편집 중인지 서버에 알린다. 서버는 60초 동안 소식이
 * 없으면 나를 뺀다. sheet는 지금 활성 탭(전체면 null) — Task 5: 탭을 바꾸면 change-debounce 경로로
 * 다시 알린다(편집 중인 셀이 바뀔 때와 같은 처리).
 */
export function usePresence(projectId: number, me: ReviewerRef | null, editingSegmentId: number | null, onSnapshot: (p: PresenceEntry[]) => void, sheet: string | null = null) {
  const snapshot = useRef(onSnapshot)
  // 렌더 중에 ref를 바꾸지 않고, 커밋 뒤 이펙트에서 최신 콜백으로 맞춘다.
  useEffect(() => { snapshot.current = onSnapshot })
  const meId = me?.id ?? null
  const editingRef = useRef(editingSegmentId)
  useEffect(() => { editingRef.current = editingSegmentId })
  const sheetRef = useRef(sheet)
  useEffect(() => { sheetRef.current = sheet })

  const beat = (id: number, editing: number | null, sh: string | null) => {
    api.presence.touch(id, { sheet: sh, editingSegmentId: editing })
      .then((r) => snapshot.current(r.reviewers))
      .catch(() => { /* 다음 heartbeat가 다시 알린다 */ })
  }

  // 접속 알림: 마운트 시 한 번 + 25초마다. 편집 중인 셀·시트가 바뀌어도 이 간격 자체는 다시 시작하지 않는다
  // (아래 별도 이펙트가 전환을 따로, debounce해서 알린다) — 매번 최신 값을 보낸다.
  useEffect(() => {
    if (meId === null) return
    beat(projectId, editingRef.current, sheetRef.current)
    const timer = setInterval(() => beat(projectId, editingRef.current, sheetRef.current), HEARTBEAT_MS)
    return () => clearInterval(timer)
  }, [projectId, meId])

  // 편집 중인 셀이나 활성 시트가 실제로 바뀔 때만(마운트 시의 초깃값은 위 이펙트가 이미 알렸으므로
  // 건너뛴다) 300ms 뒤에 알린다.
  const prevEditing = useRef(editingSegmentId)
  const prevSheet = useRef(sheet)
  useEffect(() => {
    if (meId === null) return
    if (editingSegmentId === prevEditing.current && sheet === prevSheet.current) return
    prevEditing.current = editingSegmentId
    prevSheet.current = sheet
    const timer = setTimeout(() => beat(projectId, editingSegmentId, sheet), CHANGE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [projectId, meId, editingSegmentId, sheet])

  useEffect(() => {
    if (meId === null) return
    const idAtSetup = meId   // 언마운트/전환 시 그때의 나를 떠나보낸다 — 그 사이 검토자가 바뀌었어도 새 id로 나가면 안 된다.
    const leave = () => { api.presence.leave(projectId, idAtSetup).catch(() => {}) }
    // 탭을 닫거나 주소창으로 다른 곳에 가면 이 이펙트의 cleanup은 돌지 않는다 — DELETE에 keepalive를
    // 달아 둔 건 바로 이 경우를 위해서다. 정상 언마운트에서는 pagehide가 오지 않으므로 두 번 나가지 않는다.
    window.addEventListener('pagehide', leave)
    return () => { window.removeEventListener('pagehide', leave); leave() }
  }, [projectId, meId])
}
