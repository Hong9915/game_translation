import { useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import type { PolishProposal, WorkspaceRow } from '../../api/types'
import { runChunks } from '../../lib/chunkRun'
import { useGptInstructions } from '../../lib/gptInstructions'
import { inputClass } from '../ui/Field'
import { Button } from '../ui/Button'

/** 한 번에 보내는 행 수. 서버는 50행까지 받고, GPT는 느려서 더 잘게 나눈다. */
const CHUNK = 20

export interface PolishDone { activityIds: number[]; saved: number; skipped: number; propagated: number }

interface Props {
  rows: WorkspaceRow[]
  gameId: number | null | undefined
  /** 넣은 뒤 알린다(되돌리기 한 단위·안내·목록 다시 읽기는 부르는 쪽이). */
  onDone: (r: PolishDone) => void
  onClose: () => void
}

type Step = 'ask' | 'running' | 'review'

/** 체크한 제안이 같은 원문으로 더 들어갈 행 수. 원문마다 한 번만 센다(dupCount는 자기 포함, 홀로면 0). */
function sameSourceCount(rows: WorkspaceRow[]): number {
  const bySrc = new Map<string, number>()
  for (const r of rows) bySrc.set(r.src, Math.max(bySrc.get(r.src) ?? 0, r.dupCount))
  let n = 0
  for (const d of bySrc.values()) n += Math.max(0, d - 1)
  return n
}

/**
 * Fix 범위 우클릭 "GPT로 다듬기"(2026-11-13): 묻기 → 도는 중 → 전/후 확인 → 넣기.
 * 확정 행은 보내지 않는다. 다듬은 안은 저장하지 않고 받기만 하고, 체크한 행만 base(받을 때 Fix)와 함께 넣는다.
 * 취소하면 받은 데까지로 전/후 확인에 간다. ⌘Enter 실행·넣기, Esc 닫기(도는 중엔 취소).
 */
export function PolishDialog({ rows, gameId, onDone, onClose }: Props) {
  const gpt = useGptInstructions(gameId, 'polish')
  const confirmed = rows.filter((r) => r.confirmed).length
  const ids = rows.filter((r) => !r.confirmed).map((r) => r.id)
  const byId = new Map(rows.map((r) => [r.id, r]))
  const [step, setStep] = useState<Step>('ask')
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<[number, number]>([0, 0])
  const [items, setItems] = useState<PolishProposal[]>([])
  const [kept, setKept] = useState(0)
  const [skipped, setSkipped] = useState(0)
  const [unchecked, setUnchecked] = useState<Set<number>>(new Set())
  const [saving, setSaving] = useState(false)
  const abort = useRef<AbortController | null>(null)
  // 넣는 중엔 Esc로 닫지 않는다(키 처리기는 한 번만 달아서 ref로 본다)
  const savingRef = useRef(false)

  // ⌘Enter도 다듬기 버튼과 같은 조건에서만 돈다(지시문 읽는 중·도는 중이면 안 함)
  const go = async () => {
    if (abort.current || ids.length === 0 || gpt.loading) return
    const ctrl = new AbortController()
    abort.current = ctrl
    setError(null)
    try { await gpt.save() }
    catch (e) { abort.current = null; setError(e instanceof Error ? e.message : String(e)); return }
    setStep('running')
    setProgress([0, ids.length])
    // 오류로 끝나도 그 전 묶음에서 받은 제안은 보여 준다
    const got: PolishProposal[] = []
    let k = 0
    let s = confirmed
    const show = () => { setItems([...got]); setKept(k); setSkipped(s); setUnchecked(new Set()) }
    try {
      await runChunks(ids, CHUNK, async (chunk) => {
        const res = await api.polish(chunk)
        got.push(...res.items)
        k += res.kept
        s += res.skipped.length
      }, { signal: ctrl.signal, onProgress: (done, total) => setProgress([done, total]) })
      show()
      setStep('review')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      show()
      setStep(got.length > 0 ? 'review' : 'ask')
    } finally {
      abort.current = null
    }
  }

  const checked = items.filter((p) => !unchecked.has(p.segmentId))
  const put = async () => {
    if (savingRef.current || checked.length === 0) return
    savingRef.current = true
    setSaving(true)
    setError(null)
    try {
      const res = await api.saveBatch(checked.map((p) => ({ segmentId: p.segmentId, text: p.after, base: p.before })))
      onDone({ activityIds: res.activityIds, saved: res.saved, skipped: res.skipped, propagated: res.propagated })
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      savingRef.current = false
      setSaving(false)
    }
  }
  const toggle = (id: number) => setUnchecked((prev) => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })

  // 도는 중 Esc는 취소(지금 묶음이 끝나면 받은 데까지로 넘어간다), 아니면 닫기
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      if (abort.current) abort.current.abort(); else if (!savingRef.current) onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])

  const running = step === 'running'
  const extra = sameSourceCount(checked.flatMap((p) => { const r = byId.get(p.segmentId); return r ? [r] : [] }))
  const wide = step === 'review' && items.length > 0
  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="GPT로 다듬기"
      onMouseDown={(e) => { if (e.target === e.currentTarget && !running && !saving) onClose() }}
      onKeyDown={(e) => {
        e.stopPropagation()
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); if (step === 'ask') void go(); else if (step === 'review') void put() }
      }}>
      <div className={`pop-in flex max-h-[90vh] ${wide ? 'w-[min(960px,96vw)]' : 'w-[min(640px,96vw)]'} flex-col gap-3 rounded-2xl bg-white p-5 text-sm shadow-2xl`}>
        <h2 className="text-[15px] font-semibold text-ink">GPT로 다듬기 ({rows.length}행)</h2>
        {step !== 'review' && <>
          {confirmed > 0 && <p className="text-[13px] text-muted">확정 {confirmed}행은 건너뜁니다</p>}
          <div className="flex flex-col gap-1 text-xs text-muted">
            <span>기본 지시</span>
            <p className="max-h-40 overflow-y-auto whitespace-pre-wrap rounded-lg bg-canvas px-3 py-2 text-[13px] leading-relaxed text-body">{gpt.base}</p>
          </div>
          <label className="flex w-full flex-col gap-1 text-xs text-muted">
            <span>덧붙일 지시 · 이 게임의 다음 다듬기에도 쓰입니다</span>
            <textarea aria-label="덧붙일 지시" value={gpt.text} onChange={(e) => gpt.setText(e.target.value)} rows={3} autoFocus disabled={running}
              placeholder="예: 말투는 반말로 맞춘다."
              className={`${inputClass} resize-y text-[13px] leading-relaxed`} />
          </label>
        </>}
        {step === 'review' && (items.length === 0
          ? <p className="text-[13px] text-body">고칠 데가 없습니다</p>
          : <>
              {(kept > 0 || skipped > 0) && (
                <p className="flex gap-3 text-[13px] text-muted">
                  {kept > 0 && <span>고칠 데 없음 {kept}행</span>}
                  {skipped > 0 && <span>건너뜀 {skipped}행(확정·Fix 없음·자리표시자)</span>}
                </p>
              )}
              <ul className="flex min-h-0 flex-col divide-y divide-line overflow-y-auto rounded-lg border border-line">
                {items.map((p) => {
                  const r = byId.get(p.segmentId)
                  const on = !unchecked.has(p.segmentId)
                  return (
                    <li key={p.segmentId} className={`flex gap-3 px-3 py-2 ${on ? '' : 'opacity-50'}`}>
                      <input type="checkbox" checked={on} onChange={() => toggle(p.segmentId)} aria-label={`${r?.rowNo ?? p.segmentId}행 넣기`} className="mt-1 flex-none" />
                      <div className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-x-3 gap-y-1 text-[13px] leading-relaxed">
                        <p className="row-span-2 whitespace-pre-wrap break-words text-muted">{r?.src ?? ''}</p>
                        <p className="whitespace-pre-wrap break-words text-body">{p.before}</p>
                        <p className="whitespace-pre-wrap break-words font-medium text-ink">{p.after}</p>
                        {p.reason && <p className="col-start-2 text-xs text-muted">{p.reason}</p>}
                      </div>
                    </li>
                  )
                })}
              </ul>
            </>)}
        {error && <p role="alert" className="text-[13px] text-orange">{error}</p>}
        <div className="flex items-center justify-end gap-2">
          <span className="mr-auto text-xs text-muted" aria-live="polite">
            {running ? `${progress[0]}/${progress[1]}행`
              : step === 'ask' ? (gpt.changed ? '다듬으면 고친 지시문이 이 게임에 저장됩니다' : '')
              : extra > 0 ? `같은 원문 ${extra}행에도 들어갑니다` : ''}
          </span>
          {running && <Button onClick={() => abort.current?.abort()}>취소</Button>}
          {step === 'ask' && <>
            <Button onClick={onClose}>취소</Button>
            <Button variant="primary" disabled={gpt.loading || ids.length === 0} onClick={() => void go()}>다듬기</Button>
          </>}
          {step === 'review' && (items.length === 0
            ? <Button onClick={onClose}>닫기</Button>
            : <>
                <Button onClick={onClose} disabled={saving}>취소</Button>
                <Button variant="primary" disabled={saving || checked.length === 0} onClick={() => void put()}>넣기 ({checked.length}행)</Button>
              </>)}
        </div>
      </div>
    </div>
  )
}
