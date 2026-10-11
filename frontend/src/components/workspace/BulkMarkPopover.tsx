import { useEffect, useRef } from 'react'
import { Anchored } from '../ui/Anchored'

export interface BulkMarkPopoverProps {
  x: number
  y: number
  /** 머리글 이름(1차·2차·확인 필요·확정). */
  label: string
  /** 적용될 행 수 — 쪽 보기면 그 쪽, 전체 보기면 검색·필터에 걸린 전부. */
  count: number
  onApply: (on: boolean) => void
  onClose: () => void
}

/**
 * 체크 머리글 더블클릭으로 여는 일괄 체크 창(설계 2026-09-28 §5). 실수로 몇만 행이 바뀌지 않게 몇 행인지 먼저 보이고
 * 모두 체크·모두 해제를 고르게 한다. 바깥 클릭·Esc로 닫히고 화면 밖으로 나가지 않는다(ShortenPopover와 같은 방식).
 */
export function BulkMarkPopover({ x, y, label, count, onApply, onClose }: BulkMarkPopoverProps) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onDown = (e: globalThis.MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose() }
    const onEsc = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); onClose() } }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc, true)
    return () => { document.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onEsc, true) }
  }, [onClose])

  const btn = 'press rounded-md px-2.5 py-1'
  return (
    <Anchored ref={ref} role="dialog" aria-label={`${label} 일괄 체크`} x={x} y={y}
      onKeyDown={(e) => e.stopPropagation()}
      className="glass pop-in z-50 origin-top-left rounded-xl border border-line/70 p-2.5 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
      <div className="mb-2 text-body">이 보기의 <span className="font-semibold">{count.toLocaleString()}행</span> — {label}</div>
      <div className="flex gap-1">
        <button type="button" autoFocus onClick={() => onApply(true)} className={`${btn} bg-[#107c41] font-medium text-white hover:bg-[#0d6a37]`}>모두 체크</button>
        <button type="button" onClick={() => onApply(false)} className={`${btn} border border-line bg-white text-body hover:bg-canvas`}>모두 해제</button>
        <button type="button" onClick={onClose} className={`${btn} text-body hover:bg-black/5`}>취소</button>
      </div>
    </Anchored>
  )
}
