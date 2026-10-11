import { Button } from '../ui/Button'
import { inputClass } from '../ui/Field'

export interface SheetChipInfo { name: string; rows: number }
export interface AddOption { name: string; rowCount: number }

interface Props {
  chips: SheetChipInfo[]
  active: string | null
  addOptions: AddOption[]
  adding: boolean
  confirmingRemove: string | null
  removing: boolean
  onSelect: (name: string) => void
  onStartAdd: () => void
  onCancelAdd: () => void
  onPickAdd: (name: string) => void
  onAskRemove: (name: string) => void
  onCancelRemove: () => void
  onConfirmRemove: (name: string) => void
}

/** 설정된 시트 칩 목록 + 아직 설정하지 않은 시트를 고르는 "+ 시트 추가". 시트 빼기는 줄 안에서 2단계로 확인한다. */
export function SheetChips({
  chips, active, addOptions, adding, confirmingRemove, removing,
  onSelect, onStartAdd, onCancelAdd, onPickAdd, onAskRemove, onCancelRemove, onConfirmRemove,
}: Props) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {chips.map((c) => (
        confirmingRemove === c.name ? (
          <span key={c.name} className="inline-flex items-center gap-1.5 rounded-md border border-orange bg-orangeSoft px-2 py-1 text-xs text-ink">
            <span>{c.name} 시트를 뺄까요? 번역·수정본·담당·완료가 초기화됩니다.</span>
            <Button variant="danger" onClick={() => onConfirmRemove(c.name)} disabled={removing} className="!px-2 !py-1 !text-xs">빼기 확인</Button>
            <Button onClick={onCancelRemove} disabled={removing} className="!px-2 !py-1 !text-xs">취소</Button>
          </span>
        ) : (
          <span key={c.name} className="inline-flex items-center overflow-hidden rounded-md border border-line">
            <button
              type="button"
              aria-pressed={active === c.name}
              onClick={() => onSelect(c.name)}
              className={`px-3 py-1.5 text-xs font-medium ${active === c.name ? 'bg-primary text-white' : 'bg-white text-ink hover:bg-canvas'}`}
            >
              {c.name} · {c.rows.toLocaleString()}행
            </button>
            <button
              type="button"
              aria-label={`${c.name} 빼기`}
              onClick={() => onAskRemove(c.name)}
              className="border-l border-line px-2 py-1.5 text-xs text-muted hover:bg-orangeSoft hover:text-orange"
            >
              ✕
            </button>
          </span>
        )
      ))}
      {addOptions.length > 0 && (
        adding ? (
          <span className="inline-flex items-center gap-1.5">
            <select
              aria-label="추가할 시트"
              className={`${inputClass} w-auto !py-1`}
              value=""
              onChange={(e) => { if (e.target.value) onPickAdd(e.target.value) }}
            >
              <option value="">시트 선택…</option>
              {addOptions.map((s) => <option key={s.name} value={s.name}>{s.name} ({s.rowCount.toLocaleString()}행)</option>)}
            </select>
            <Button onClick={onCancelAdd} className="!px-2 !py-1 !text-xs">취소</Button>
          </span>
        ) : (
          <Button onClick={onStartAdd} className="!px-2.5 !py-1 !text-xs">+ 시트 추가</Button>
        )
      )}
    </div>
  )
}
