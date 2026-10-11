import { useEffect, useRef } from 'react'
import type { JosaHint } from '../../api/types'
import { josaLabel } from '../../lib/josa'
import { Anchored } from '../ui/Anchored'

export interface JosaPopoverProps {
  x: number
  y: number
  fix: string
  hints: JosaHint[]
  onApply: () => void
  onIgnore: () => void
  onClose: () => void
}

/** Fix 셀 `조사` 배지를 누르면 뜨는 창(설계 2026-10-07 §6). 바깥 클릭·Esc로 닫힌다(BulkMarkPopover와 같은 방식). */
export function JosaPopover({ x, y, fix, hints, onApply, onIgnore, onClose }: JosaPopoverProps) {
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
    <Anchored ref={ref} role="dialog" aria-label="조사 제안" x={x} y={y} onKeyDown={(e) => e.stopPropagation()}
      className="glass pop-in z-50 origin-top-left rounded-xl border border-line/70 p-2.5 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
      <ul className="mb-2 space-y-0.5 text-body">
        {hints.map((h) => <li key={`${h.at}:${h.from}`}>{josaLabel(fix, h)}</li>)}
      </ul>
      {/* 확정 행도 고친다(2026-10-07 사용자 결정) — 조사 고침은 받침만 맞추는 일이라 잠그지 않는다. */}
      <div className="flex gap-1">
        <button type="button" autoFocus onClick={onApply} className={`${btn} bg-[#107c41] font-medium text-white hover:bg-[#0d6a37]`}>적용</button>
        <button type="button" onClick={onIgnore} className={`${btn} border border-line bg-white text-body hover:bg-canvas`}>무시</button>
      </div>
    </Anchored>
  )
}
