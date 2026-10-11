import { useMutation } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { GlossaryVariant } from '../../api/types'
import { spacingTarget, type SpacingPolicy } from '../../lib/spacing'
import { Button } from '../ui/Button'

interface Props {
  gameId: number; variants: GlossaryVariant[]; policy: SpacingPolicy
  onClose: () => void
  /** 고친 구절(원문 → 맞춘 꼴)과 서버가 고친 행 수. */
  onDone: (fixed: { find: string; ko: string }[], applied: number) => void
}

/**
 * 띄어쓰기 한꺼번에 통일 창. 남은 띄어쓰기 카드마다 맞출 꼴과 바꿀 꼴을 보여 주고, 뺄 구절의 체크를 풀게 한다.
 * 행만 고친다 — 단어장엔 남기지 않는다. 행 수는 카드에 보인 수의 합이라 확정 행이 있으면 실제보다 많을 수 있다.
 */
export function SpacingBulkDialog({ gameId, variants, policy, onClose, onDone }: Props) {
  const items = variants.map((v) => ({ v, ...spacingTarget(v, policy) })).filter((x) => x.replace.length > 0)
  const [skipped, setSkipped] = useState<ReadonlySet<string>>(new Set())
  const chosen = items.filter((x) => !skipped.has(x.v.find))
  const fix = useMutation({
    mutationFn: () => api.glossary.unifySpacing(gameId, chosen.map((x) => ({ find: x.v.find, ko: x.ko, replace: x.replace }))),
    onSuccess: (r) => onDone(chosen.map((x) => ({ find: x.v.find, ko: x.ko })), r.applied),
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const toggle = (find: string) => setSkipped((s) => { const n = new Set(s); if (!n.delete(find)) n.add(find); return n })
  const rows = chosen.reduce((n, x) => n + x.rows, 0)

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="띄어쓰기 한꺼번에 통일">
      <form className="pop-in flex max-h-[90vh] w-full max-w-xl flex-col rounded-2xl bg-white p-5 shadow-2xl"
        onSubmit={(e) => { e.preventDefault(); if (chosen.length) fix.mutate() }}>
        <h2 className="text-[15px] font-semibold text-ink">띄어쓰기 한꺼번에 통일</h2>
        <p className="mt-1 break-keep text-xs text-body">
          {policy === 'standard' ? '표준 띄어쓰기(Kiwi)로, 표준 꼴이 없는 구절은 많이 쓴 꼴로' : '게임에서 많이 쓴 꼴로'} 맞춥니다.
          원문에 그 구절이 든 행만 고치고, 단어장에는 남기지 않습니다. 확정한 행은 건드리지 않습니다.
        </p>
        <div className="mt-3 min-h-0 flex-1 overflow-y-auto">
          {items.map((x) => (
            <label key={x.v.find} className="flex cursor-pointer items-center gap-2 border-t border-dashed border-line py-1.5 text-sm">
              <input type="checkbox" checked={!skipped.has(x.v.find)} onChange={() => toggle(x.v.find)}
                aria-label={`${x.v.find} ${x.replace.join(', ')} → ${x.ko}`} />
              <span className="w-28 shrink-0 truncate">{x.v.find}</span>
              <span className="min-w-0 flex-1 break-keep">{x.replace.join(', ')} → <b>{x.ko}</b></span>
              <span className="shrink-0 text-xs tabular-nums text-muted">약 {x.rows.toLocaleString()}행</span>
            </label>
          ))}
        </div>
        {fix.isError && <p role="alert" className="mt-2 text-xs text-orange">{fix.error.message}</p>}
        <div className="mt-4 flex items-center justify-end gap-2">
          <span className="mr-auto text-xs tabular-nums text-muted">{chosen.length.toLocaleString()}개 구절 · 약 {rows.toLocaleString()}행</span>
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={chosen.length === 0 || fix.isPending}>고치기</Button>
        </div>
      </form>
    </div>
  )
}
