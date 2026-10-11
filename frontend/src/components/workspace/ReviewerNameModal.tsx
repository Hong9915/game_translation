import { useEffect, useState } from 'react'
import { Button } from '../ui/Button'
import { inputClass } from '../ui/Field'

interface Props { open: boolean; initialName?: string; onSubmit: (name: string) => Promise<unknown>; onClose?: () => void }

export function ReviewerNameModal({ open, initialName = '', onSubmit, onClose }: Props) {
  const [name, setName] = useState(initialName)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 열 때마다 지금 이름으로 되돌린다 — 지난번에 쓰다 만 글자가 남아 있으면 엉뚱한 사람으로 바뀐다.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) { setName(initialName); setError(null) }
  }
  useEffect(() => {
    if (!open || !onClose) return   // 이름이 아직 없으면 닫을 수 없다
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!open) return null
  const submit = async () => {
    const v = name.trim()
    if (!v || busy) return
    setBusy(true)
    setError(null)
    try { await onSubmit(v) } catch (e) { setError(e instanceof Error ? e.message : '이름을 저장하지 못했습니다.') } finally { setBusy(false) }
  }
  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30" role="dialog" aria-modal="true" aria-label="검토자 이름">
      <form className="pop-in w-80 rounded-2xl bg-white p-5 shadow-2xl" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <h2 className="text-[15px] font-semibold text-ink">이름을 입력하세요</h2>
        <p className="mt-1 text-xs text-body">수정 기록과 담당 표시에 이 이름이 쓰입니다. 이 브라우저에 저장됩니다.</p>
        <input aria-label="이름" autoFocus className={`${inputClass} mt-3 w-full`} value={name} onChange={(e) => setName(e.target.value)} />
        {error && <div className="mt-2 text-xs text-orange">{error}</div>}
        <div className="mt-4 flex justify-end gap-2">
          {onClose && <Button type="button" onClick={onClose}>취소</Button>}
          <Button type="submit" variant="primary" disabled={busy}>시작</Button>
        </div>
      </form>
    </div>
  )
}
