import { useEffect, useState } from 'react'
import type { WorkspaceRow } from '../../api/types'
import { instructionsProblem, useDeepLInstructions } from '../../lib/deeplInstructions'
import { DeepLInstructionsField } from '../DeepLInstructionsField'
import { Button } from '../ui/Button'

interface Props {
  row: WorkspaceRow
  gameId: number | null | undefined
  /** 지시문을 저장한 뒤 부른다 — 번역은 부르는 쪽(행 번역 mutation)이 한다. */
  onTranslate: (row: WorkspaceRow) => void
  onClose: () => void
}

/** 원문 우클릭 "DeepL 번역"의 확인 창(2026-10-26): 그 게임의 DeepL 지시문을 보이고, 고쳤으면 저장한 뒤 번역한다. ⌘Enter로도 번역. */
export function TranslateRowDialog({ row, gameId, onTranslate, onClose }: Props) {
  const instructions = useDeepLInstructions(gameId)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const go = async () => {
    setBusy(true); setError(null)
    try { await instructions.save(); onTranslate(row); onClose() }
    catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])
  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="DeepL 번역"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}
      onKeyDown={(e) => { e.stopPropagation(); if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); void go() } }}>
      <div className="pop-in flex w-[min(640px,96vw)] flex-col gap-3 rounded-2xl bg-white p-5 text-sm shadow-2xl">
        <h2 className="text-[15px] font-semibold text-ink">DeepL 번역</h2>
        <p className="line-clamp-3 break-all rounded-lg bg-canvas px-3 py-2 text-[13px] text-body">{row.src}</p>
        <DeepLInstructionsField text={instructions.text} onChange={instructions.setText} autoFocus />
        {error && <p role="alert" className="text-[13px] text-orange">{error}</p>}
        <div className="flex items-center justify-end gap-2">
          <span className="mr-auto text-xs text-muted">{instructions.changed ? '번역하면 고친 지시문이 이 게임에 저장됩니다' : ''}</span>
          <Button onClick={onClose}>취소</Button>
          <Button variant="primary" disabled={busy || instructions.loading || !!instructionsProblem(instructions.text)} onClick={() => void go()}>번역</Button>
        </div>
      </div>
    </div>
  )
}
