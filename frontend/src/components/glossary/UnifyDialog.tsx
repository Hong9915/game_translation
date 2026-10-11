import { useMutation, useQueries } from '@tanstack/react-query'
import { useDeferredValue, useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { GlossaryVariant } from '../../api/types'
import { replacePartial, ro } from '../../lib/korean'
import { Button } from '../ui/Button'
import { inputClass } from '../ui/Field'

interface Props { gameId: number; v: GlossaryVariant; onClose: () => void; onDone: (message: string) => void }

/**
 * 표기 통일 창(설계 2026-10-07 §3). 맞출 표기 하나를 고르고, 나머지 표기마다 바꿀지 체크한다. 체크한 표기만 서버가 행에서 고치고,
 * 남긴 표기가 있으면 단어장엔 메모로 남는다. 바뀔 행 수는 기존 미리보기 API로, 예문의 전/후는 서버와 같은 치환(replacePartial)으로 보여 준다.
 */
export function UnifyDialog({ gameId, v, onClose, onDone }: Props) {
  const options = [v.main, ...v.alts]
  const [picked, setPicked] = useState<string>(v.main.ko)   // 표기 또는 '' = 직접 입력
  const [custom, setCustom] = useState('')
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set())
  const ko = (picked === '' ? custom : picked).trim()
  const others = options.filter((o) => o.ko !== ko)
  const replace = others.filter((o) => !unchecked.has(o.ko)).map((o) => o.ko)
  const keep = others.filter((o) => unchecked.has(o.ko)).map((o) => o.ko)

  // 직접 입력 중엔 글자마다 표기 수만큼 요청이 나가지 않게, 바뀔 행 수는 늦춰진 값으로 묻는다
  const countKo = useDeferredValue(ko)
  const counts = useQueries({
    queries: others.map((o) => ({
      queryKey: ['glossary', gameId, 'COUNT', v.find, countKo, o.ko],
      queryFn: () => api.glossary.count(gameId, { kind: 'SRC', matchMode: 'PARTIAL', find: v.find, ko: countKo, oldKo: o.ko }),
      enabled: countKo.length > 0,
    })),
  })
  const unify = useMutation({
    mutationFn: () => api.glossary.unify(gameId, { find: v.find, ko, replace, keep }),
    onSuccess: (r) => onDone(`${ko}${ro(ko)} 통일 · ${r.applied.toLocaleString()}행`),
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const toggle = (k: string) => setUnchecked((s) => { const n = new Set(s); if (!n.delete(k)) n.add(k); return n })

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label={`${v.find} 통일`}>
      <form className="pop-in max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-2xl bg-white p-5 shadow-2xl"
        onSubmit={(e) => { e.preventDefault(); if (replace.length && ko) unify.mutate() }}>
        <h2 className="text-[15px] font-semibold text-ink">「{v.find}」 표기 통일</h2>

        <fieldset className="mt-3">
          <legend className="text-xs font-semibold text-muted">맞출 표기</legend>
          {options.map((o) => (
            <label key={o.ko} className="flex cursor-pointer items-center gap-2 py-0.5 text-sm">
              <input type="radio" name="target" checked={picked === o.ko} onChange={() => setPicked(o.ko)} />
              {o.ko} · {o.rows.toLocaleString()}행
            </label>
          ))}
          <label className="flex cursor-pointer items-center gap-2 py-0.5 text-sm">
            <input type="radio" name="target" checked={picked === ''} onChange={() => setPicked('')} />직접 입력
          </label>
          {picked === '' && <input aria-label="맞출 표기 직접 입력" autoFocus className={`${inputClass} mt-1 w-full`} value={custom} onChange={(e) => setCustom(e.target.value)} />}
        </fieldset>

        <fieldset className="mt-4">
          <legend className="text-xs font-semibold text-muted">바꿀 표기 — 체크한 표기가 든 행을 「{ko || '…'}」(으)로 고칩니다</legend>
          {others.map((o, i) => {
            const e = o.examples.find((x) => x.ko.includes(o.ko))
            return (
              <div key={o.ko} className="border-t border-dashed border-line py-1.5">
                <label className="flex cursor-pointer items-center gap-2 text-sm">
                  <input type="checkbox" checked={!unchecked.has(o.ko)} onChange={() => toggle(o.ko)} aria-label={o.ko} />
                  <span className="font-semibold">{o.ko}</span>
                  <span className="text-xs tabular-nums text-muted">{counts[i]?.data ? `${counts[i].data.count.toLocaleString()}행 바뀜` : '…'}</span>
                </label>
                {e && !unchecked.has(o.ko) && ko && (
                  <div className="ml-6 mt-0.5 text-xs">
                    <div className="text-muted line-through">{e.ko}</div>
                    <div>{replacePartial(e.ko, o.ko, ko)}</div>
                  </div>
                )}
              </div>
            )
          })}
        </fieldset>

        {keep.length > 0 && replace.length > 0 && <p className="mt-3 text-xs text-body">남긴 표기가 있어 단어장에는 메모로 남습니다.</p>}
        {unify.isError && <p role="alert" className="mt-2 text-xs text-orange">{unify.error.message}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={onClose}>취소</Button>
          <Button type="submit" variant="primary" disabled={!ko || replace.length === 0 || unify.isPending}>통일</Button>
        </div>
      </form>
    </div>
  )
}
