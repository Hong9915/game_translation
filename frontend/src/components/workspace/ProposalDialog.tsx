import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../../api/client'
import type { ProposalResult } from '../../api/types'
import { Button } from '../ui/Button'

export interface ProposalDialogProps {
  proposalId: number
  /** [지금 몇 번째, 전체]. 전체가 1이면 보이지 않는다. */
  position: [number, number]
  /** 저장 뒤(되돌리기·안내·목록 다시 읽기는 부르는 쪽). */
  onSaved: (r: ProposalResult) => void
  onDismissed: () => void
  onLater: () => void
  /** 409 또는 읽어 보니 PENDING이 아님 → 부르는 쪽이 안내하고 목록에서 뺀다. */
  onGone: (message: string) => void
}

const DECIDED = '이미 처리된 제안입니다.'
const FINAL_NOTE = "최종 리뷰 시트라 저장하면 '최종 수정'이 켜집니다"

/**
 * Claude가 커넥터로 보낸 고칠 안을 검수자가 확인하는 창. 잠기지 않은 행이 기본 체크이고, 체크한 행만 저장한다.
 * 배경 클릭·Esc = 나중에(저장 중엔 무시), ⌘/Ctrl+Enter = 저장.
 */
export function ProposalDialog({ proposalId, position, onSaved, onDismissed, onLater, onGone }: ProposalDialogProps) {
  const q = useQuery({ queryKey: ['proposal', proposalId], queryFn: () => api.proposals.get(proposalId), staleTime: 0, refetchOnMount: 'always', gcTime: 0, retry: false })
  const d = q.data
  // 기본값(원문에 용어가 없는 행만 꺼짐)에서 사람이 뒤집은 행. 상세를 다시 읽어도 유지된다.
  const [flipped, setFlipped] = useState<Set<number>>(new Set())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 키 처리기는 한 번만 달아서 ref로 본다. 두 번 누름도 여기서 막는다.
  const busyRef = useRef(false)
  const goneRef = useRef(false)
  const saveRef = useRef<(() => void) | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const prevFocus = useRef<Element | null>(typeof document === 'undefined' ? null : document.activeElement)

  const gone = (message: string) => {
    if (goneRef.current) return
    goneRef.current = true
    onGone(message)
  }
  useEffect(() => {
    if (d && d.status !== 'PENDING') gone(DECIDED)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [d])

  const notFound = q.error instanceof ApiError && q.error.status === 404
  useEffect(() => {
    if (notFound) gone('제안을 찾을 수 없습니다.')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notFound])

  const shown = !!d && d.status === 'PENDING'
  // 읽은 창이 있으면 배경 새로고침이 실패해도 그대로 둔다
  const errored = q.isError && !q.data && !notFound
  const visible = shown || errored

  // 창은 라이브 이벤트로 저절로 뜨므로 포커스가 그리드에 있을 수 있다. 키는 창 단계(capture)에서 가로채 아래 화면으로 새지 않게 한다.
  // 보이는 동안에만 단다 — 읽는 중에 누른 Esc는 아래 화면(다른 창 포함)의 것이다.
  useEffect(() => {
    if (!visible) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        if (!busyRef.current) onLater()
      } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.stopPropagation()
        e.preventDefault()
        saveRef.current?.()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onLater, visible])

  useEffect(() => {
    if (shown || errored) panelRef.current?.focus()
  }, [shown, errored])
  useEffect(() => {
    const prev = prevFocus.current
    return () => { if (prev instanceof HTMLElement && prev.isConnected) prev.focus() }
  }, [])

  if (errored) {
    return (
      <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="Claude가 고칠 안"
        onMouseDown={(e) => { if (e.target === e.currentTarget) onLater() }}>
        <div ref={panelRef} tabIndex={-1} className="pop-in flex w-[min(480px,96vw)] flex-col gap-3 rounded-2xl bg-white p-5 text-sm shadow-2xl outline-none">
          <h2 className="text-[15px] font-semibold text-ink">Claude가 고칠 안</h2>
          <p role="alert" className="text-[13px] text-orange">{q.error instanceof Error ? q.error.message : '제안을 읽지 못했습니다.'}</p>
          <div className="flex justify-end"><Button onClick={onLater}>나중에</Button></div>
        </div>
      </div>
    )
  }
  if (!d || d.status !== 'PENDING') return null

  const rows = d.rows
  const isOn = (r: (typeof rows)[number]) => r.lockedReason == null && (!r.srcMissing !== flipped.has(r.segmentId))
  const checked = rows.filter(isOn)
  const finalOn = checked.some((r) => r.finalReview)
  const toggle = (id: number) => setFlipped((prev) => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })

  const run = async <T,>(call: () => Promise<T>, done: (r: T) => void) => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setError(null)
    try {
      done(await call())
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) { gone(e.message); return }
      setError(e instanceof Error ? e.message : String(e))
      busyRef.current = false
      setBusy(false)
    }
  }
  const save = () => {
    if (checked.length === 0) return
    void run(() => api.proposals.apply(proposalId, checked.map((r) => r.segmentId)), (res) => {
      if (res.result) onSaved(res.result)
      else gone(DECIDED)
    })
  }
  saveRef.current = save
  const dismiss = () => void run(() => api.proposals.dismiss(proposalId), () => onDismissed())

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="Claude가 고칠 안"
      onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onLater() }}
      onKeyDown={(e) => e.stopPropagation()}>
      <div ref={panelRef} tabIndex={-1} className="pop-in flex max-h-[90vh] w-[min(960px,96vw)] flex-col gap-3 rounded-2xl bg-white p-5 text-sm shadow-2xl outline-none">
        <div className="flex items-baseline gap-2">
          <h2 className="text-[15px] font-semibold text-ink">Claude가 고칠 안{d.title ? ` — ${d.title}` : ''} ({rows.length}행)</h2>
          {position[1] > 1 && <span className="text-xs text-muted">{position[0]}/{position[1]}</span>}
        </div>
        <ul className="flex min-h-0 flex-col divide-y divide-line overflow-y-auto rounded-lg border border-line">
          {rows.map((r) => {
            const locked = r.lockedReason != null
            const on = isOn(r)
            return (
              <li key={r.segmentId} className={`flex gap-3 px-3 py-2 ${on ? '' : 'opacity-50'}`}>
                <input type="checkbox" checked={on} disabled={locked || busy} onChange={() => toggle(r.segmentId)}
                  aria-label={`${r.rowNo ?? r.segmentId}행 저장`} className="mt-1 flex-none" />
                <div className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-x-3 gap-y-1 text-[13px] leading-relaxed">
                  <div className="row-span-3 min-w-0">
                    <p className="flex flex-wrap items-baseline gap-x-2 text-xs text-muted">
                      <span className="font-medium text-ink">#{r.rowNo ?? r.segmentId}</span>
                      <span>{[r.fileName, r.sheet].filter(Boolean).join(' / ')}</span>
                    </p>
                    <p className="whitespace-pre-wrap break-words text-muted">{r.src ?? ''}</p>
                  </div>
                  <p className="whitespace-pre-wrap break-words text-body">{r.fix ?? ''}</p>
                  <p className="whitespace-pre-wrap break-words font-medium text-ink">{r.text}</p>
                  <div className="col-start-2 flex flex-wrap gap-x-3 text-xs text-muted">
                    {r.reason && <span>이유: {r.reason}</span>}
                    {locked && <span className="text-orange">🔒 {r.lockedReason}</span>}
                    {r.srcMissing && <span className="text-orange">원문에 {d.termSrc ?? '용어'} 없음</span>}
                  </div>
                </div>
              </li>
            )
          })}
        </ul>
        {error && <p role="alert" className="text-[13px] text-orange">{error}</p>}
        <div className="flex items-center justify-end gap-2">
          <span className="mr-auto text-xs text-muted" aria-live="polite">{finalOn ? FINAL_NOTE : ''}</span>
          <Button onClick={onLater} disabled={busy}>나중에</Button>
          <Button onClick={dismiss} disabled={busy}>넣지 않음</Button>
          <Button variant="primary" disabled={busy || checked.length === 0} onClick={save}>체크한 {checked.length}행 저장</Button>
        </div>
      </div>
    </div>
  )
}
