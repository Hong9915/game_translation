import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import type { ReviewTarget } from '../../lib/reviewTarget'
import type { JosaKind } from '../../api/types'
import { Anchored } from '../ui/Anchored'

const KINDS: { kind: JosaKind; label: string; note?: string }[] = [
  { kind: 'HANGUL', label: '한글 뒤' },
  { kind: 'NUMBER', label: '숫자 뒤' },
  { kind: 'TAG', label: '태그 뒤', note: '오탐 섞임' },
]

export interface JosaApplyDialogProps {
  x: number
  y: number
  target: ReviewTarget
  onDone: (r: { changed: number; skipped: number }) => void
  onClose: () => void
}

/** 조사 확인 필터의 "모두 적용" 확인 창(설계 2026-10-07 §6). 판정이 확실한 한글 뒤·숫자 뒤만 기본으로 켠다. */
export function JosaApplyDialog({ x, y, target, onDone, onClose }: JosaApplyDialogProps) {
  const ref = useRef<HTMLDivElement>(null)
  const [on, setOn] = useState<Record<JosaKind, boolean>>({ HANGUL: true, NUMBER: true, TAG: false })
  const preview = useQuery({ queryKey: ['josa-preview', target], queryFn: () => api.review(target).josaPreview() })
  const kinds = KINDS.filter((k) => on[k.kind]).map((k) => k.kind)
  const total = kinds.reduce((n, k) => n + (preview.data?.counts[k] ?? 0), 0)
  const apply = useMutation({ mutationFn: () => api.review(target).josaApply({ kinds }), onSuccess: (r) => onDone(r) })

  useEffect(() => {
    const onDown = (e: globalThis.MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose() }
    const onEsc = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); onClose() } }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc, true)
    return () => { document.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onEsc, true) }
  }, [onClose])

  const btn = 'press rounded-md px-2.5 py-1'
  return (
    <Anchored ref={ref} role="dialog" aria-label="조사 모두 적용" x={x} y={y} onKeyDown={(e) => e.stopPropagation()}
      className="glass pop-in z-50 w-80 origin-top-right rounded-xl border border-line/70 p-3 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
      {preview.isPending ? <div className="text-muted">세는 중…</div> : preview.isError ? <div className="text-red-600">미리보기를 불러오지 못했습니다.</div> : (
        <>
          <ul className="mb-2 space-y-1">
            {KINDS.map((k) => (
              <li key={k.kind}>
                <label className="flex items-start gap-2">
                  <input type="checkbox" checked={on[k.kind]} onChange={(e) => setOn({ ...on, [k.kind]: e.target.checked })}
                    aria-label={`${k.label} ${preview.data.counts[k.kind]}곳`} className="mt-0.5 accent-[#107c41]" />
                  <span>
                    <span className="font-medium">{k.label}</span> {preview.data.counts[k.kind].toLocaleString()}곳
                    {k.note && <span className="text-muted"> ({k.note})</span>}
                    <span className="block text-muted">{preview.data.examples[k.kind].join(', ')}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <div className="mb-2 text-muted">바뀐 행은 변경 내역에 한 건으로 남고 되돌릴 수 있습니다.</div>
          {apply.isError && <div className="mb-2 text-red-600">{apply.error instanceof Error ? apply.error.message : '고치지 못했습니다.'}</div>}
          <div className="flex justify-end gap-1">
            <button type="button" onClick={onClose} className={`${btn} text-body hover:bg-black/5`}>취소</button>
            <button type="button" autoFocus disabled={total === 0 || apply.isPending} onClick={() => apply.mutate()}
              className={`${btn} bg-[#107c41] font-medium text-white hover:bg-[#0d6a37] disabled:opacity-50`}>{total.toLocaleString()}곳 고치기</button>
          </div>
        </>
      )}
    </Anchored>
  )
}
