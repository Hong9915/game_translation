import { useState } from 'react'
import type { GlossaryCandidate } from '../../api/types'
import { Button } from '../ui/Button'

export interface CandidateCardProps {
  c: GlossaryCandidate
  onApprove: () => void
  onDismiss: () => void
  busy: boolean
}

/** '으로'/'로' 고르기: 받침 없음·ㄹ받침이면 로, 그 밖의 받침이면 으로, 한글이 아니면 (으)로. */
function euro(word: string): string {
  const code = word.trim().charCodeAt(word.trim().length - 1)
  if (!(code >= 0xac00 && code <= 0xd7a3)) return '(으)로'
  const jong = (code - 0xac00) % 28
  return jong === 0 || jong === 8 ? '로' : '으로'
}

const REASON_FOLD = 80   // 이 글자 수를 넘으면 두 줄로 접고 "더 보기"

function when(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 단어장 "승인 대기" 칸의 후보 한 장 — 누가 어떤 결정을 했는지 보고 승인하거나 버린다. */
export default function CandidateCard({ c, onApprove, onDismiss, busy }: CandidateCardProps) {
  const [open, setOpen] = useState(false)
  const long = (c.reason?.length ?? 0) > REASON_FOLD
  return (
    <div className="rounded-lg border border-lineSoft bg-white px-3 py-2">
      <div className="flex flex-wrap items-baseline gap-x-1.5 text-[13px] leading-snug">
        <span className="break-all font-medium">{`${c.src} → ${c.ko}`}</span>
        {c.oldKo && <span className="text-[11px] text-muted">{`전: ${c.oldKo}`}</span>}
      </div>
      <p className="mt-1 text-[11px] text-muted">
        {`${c.rows}행 · ${c.createdByName ?? '알 수 없음'} · ${c.source === 'CONNECTOR' ? 'Claude' : '명령으로 고치기'} · ${when(c.createdAt)}`}
      </p>
      {c.reason && (
        <p className={`mt-1 break-keep text-[11px] leading-relaxed text-body ${long && !open ? 'line-clamp-2' : ''}`}>
          {c.reason}
          {long && <button type="button" className="ml-1 text-primary" onClick={() => setOpen((o) => !o)}>{open ? '접기' : '더 보기'}</button>}
        </p>
      )}
      {c.conflictKo && <p className="mt-1 break-keep text-[11px] text-orange">{`⚠ 단어장엔 '${c.conflictKo}'${euro(c.conflictKo)} 있음`}</p>}
      <div className="mt-2 flex justify-end gap-1.5">
        <Button onClick={onDismiss} disabled={busy} className="!px-2.5 !py-1 !text-xs">버림</Button>
        <Button variant="primary" onClick={onApprove} disabled={busy} className="!px-2.5 !py-1 !text-xs">승인</Button>
      </div>
    </div>
  )
}
