import { useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import type { BatchTranslateResult, WorkspaceRow } from '../../api/types'
import { runChunks } from '../../lib/chunkRun'
import { instructionsProblem, useDeepLInstructions } from '../../lib/deeplInstructions'
import { useGptInstructions } from '../../lib/gptInstructions'
import { DeepLInstructionsField } from '../DeepLInstructionsField'
import { inputClass } from '../ui/Field'
import { Button } from '../ui/Button'

/** 한 번에 보내는 행 수. 서버는 50행까지 받고, GPT는 느려서 더 잘게 나눈다. */
const CHUNK = { DEEPL: 50, GPT: 20 } as const

export interface BatchTranslateDone { activityIds: number[]; translated: number; skipped: number }

interface Props {
  engine: 'DEEPL' | 'GPT'
  rows: WorkspaceRow[]
  gameId: number | null | undefined
  /** 묶음마다 받은 행 — 부르는 쪽이 캐시의 행을 바로 바꾼다. */
  onRows: (rows: WorkspaceRow[]) => void
  /** 지금 번역 중인 행 표시를 켜고 끈다. */
  onTranslating: (ids: number[], on: boolean) => void
  /** 끝·취소·오류 때 그때까지 한 것을 알린다(되돌리기 한 단위). */
  onDone: (r: BatchTranslateDone) => void
  onClose: () => void
}

/**
 * 원문 범위 우클릭 "DeepL 번역 / GPT 번역"(2026-11-13): 고른 여러 행을 묶음으로 나눠 차례로 다시 번역한다.
 * 확정 행은 보내지 않는다. 진행 중엔 "N/M행"과 취소, ⌘Enter 실행, Esc 닫기(실행 중엔 취소).
 */
export function BatchTranslateDialog({ engine, rows, gameId, onRows, onTranslating, onDone, onClose }: Props) {
  const deepl = useDeepLInstructions(engine === 'DEEPL' ? gameId : null)
  const gpt = useGptInstructions(engine === 'GPT' ? gameId : null, 'translate')
  const instructions = engine === 'DEEPL' ? deepl : gpt
  const label = engine === 'DEEPL' ? 'DeepL 번역' : 'GPT 번역'
  const confirmed = rows.filter((r) => r.confirmed).length
  const ids = rows.filter((r) => !r.confirmed).map((r) => r.id)
  const [error, setError] = useState<string | null>(null)
  const [progress, setProgress] = useState<[number, number] | null>(null)
  const abort = useRef<AbortController | null>(null)
  const running = progress !== null
  const problem = engine === 'DEEPL' ? instructionsProblem(deepl.text) : null

  // ⌘Enter도 번역 버튼과 같은 조건에서만 돈다(지시문 읽는 중·한도 넘음·실행 중이면 안 함)
  const go = async () => {
    if (abort.current || ids.length === 0 || instructions.loading || problem) return
    const ctrl = new AbortController()
    abort.current = ctrl
    setError(null)
    try { await instructions.save() }
    catch (e) { abort.current = null; setError(e instanceof Error ? e.message : String(e)); return }
    setProgress([0, ids.length])
    // runChunks가 오류로 끝나도 그 전 묶음이 한 일은 알려야 해서 여기서 모은다
    let last: Record<string, number> | undefined
    let translated = 0
    let skipped = confirmed
    const report = () => onDone({ activityIds: Object.values(last ?? {}), translated, skipped })
    try {
      const { cancelled } = await runChunks(ids, CHUNK[engine], async (chunk) => {
        onTranslating(chunk, true)
        try {
          const res: BatchTranslateResult = await api.translateBatch({ segmentIds: chunk, engine, ...(last ? { activityIds: last } : {}) })
          last = res.activityIds
          translated += res.rows.length
          skipped += res.skipped.length
          if (res.rows.length > 0) onRows(res.rows)
          return res
        } finally { onTranslating(chunk, false) }
      }, { signal: ctrl.signal, onProgress: (done, total) => setProgress([done, total]) })
      // 아무 묶음도 보내기 전에 취소했으면 알릴 것이 없다
      if (!cancelled || last || translated > 0) report()
      onClose()
    } catch (e) {
      if (translated > 0 || last) report()
      setError(e instanceof Error ? e.message : String(e))
      setProgress(null)
    } finally {
      abort.current = null
    }
  }
  const cancel = () => abort.current?.abort()

  // 실행 중 Esc는 취소(지금 묶음이 끝나면 멈춘다), 아니면 닫기
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      if (abort.current) abort.current.abort(); else onClose()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label={label}
      onMouseDown={(e) => { if (e.target === e.currentTarget && !running) onClose() }}
      onKeyDown={(e) => { e.stopPropagation(); if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); void go() } }}>
      <div className="pop-in flex w-[min(640px,96vw)] flex-col gap-3 rounded-2xl bg-white p-5 text-sm shadow-2xl">
        <h2 className="text-[15px] font-semibold text-ink">{label} ({rows.length}행)</h2>
        {confirmed > 0 && <p className="text-[13px] text-muted">확정 {confirmed}행은 건너뜁니다</p>}
        {engine === 'DEEPL'
          ? <DeepLInstructionsField text={deepl.text} onChange={deepl.setText} autoFocus />
          : <>
              <div className="flex flex-col gap-1 text-xs text-muted">
                <span>기본 지시</span>
                <p className="max-h-40 overflow-y-auto whitespace-pre-wrap rounded-lg bg-canvas px-3 py-2 text-[13px] leading-relaxed text-body">{gpt.base}</p>
              </div>
              <label className="flex w-full flex-col gap-1 text-xs text-muted">
                <span>덧붙일 지시 · 이 게임의 다음 GPT 번역에도 쓰입니다</span>
                <textarea aria-label="덧붙일 지시" value={gpt.text} onChange={(e) => gpt.setText(e.target.value)} rows={3} autoFocus
                  placeholder="예: 존댓말로 번역한다."
                  className={`${inputClass} resize-y text-[13px] leading-relaxed`} />
              </label>
            </>}
        {error && <p role="alert" className="text-[13px] text-orange">{error}</p>}
        <div className="flex items-center justify-end gap-2">
          <span className="mr-auto text-xs text-muted" aria-live="polite">
            {running ? `${progress[0]}/${progress[1]}행` : instructions.changed ? '번역하면 고친 지시문이 이 게임에 저장됩니다' : ''}
          </span>
          {running
            ? <Button onClick={cancel}>취소</Button>
            : <>
                <Button onClick={onClose}>취소</Button>
                <Button variant="primary" disabled={instructions.loading || ids.length === 0 || !!problem} onClick={() => void go()}>번역</Button>
              </>}
        </div>
      </div>
    </div>
  )
}
