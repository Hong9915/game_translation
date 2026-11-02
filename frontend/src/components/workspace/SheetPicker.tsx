import { useEffect, useRef, useState } from 'react'
import type { SheetSummary } from '../../api/types'

export interface SheetPickerProps {
  /** 게임의 설정 시트 전부(게임 순서). configId가 없는 시트는 고를 수 없어 빠진다. */
  sheets: SheetSummary[]
  /** 고른 설정 id. 비어 있으면 전체. */
  selected: number[]
  onChange: (ids: number[]) => void
  /** 시트 하나만 내보내기. */
  onExport: (sheet: SheetSummary) => void
  exporting: boolean
  /** 그 시트의 첫 행으로 가기(고르지 않은 시트면 범위에 넣고 간다). */
  onJump: (configId: number) => void
}

type Pickable = SheetSummary & { configId: number }

/** 고른 범위를 한 줄로: 전체 / 파일 · 시트 / 시트 N개, 뒤에 행 수. */
function scopeLabel(sheets: SheetSummary[], selected: number[]): string {
  const all = sheets.filter((s): s is Pickable => s.configId != null)
  const picked = selected.length === 0 ? all : all.filter((s) => selected.includes(s.configId))
  const rows = picked.reduce((n, s) => n + s.total, 0).toLocaleString()
  if (selected.length === 0) return `전체 시트 ${all.length}개 · ${rows}행`
  if (picked.length === 1) return `${picked[0].fileName ?? ''} · ${picked[0].sheet} · ${rows}행`
  return `시트 ${picked.length}개 · ${rows}행`
}

/**
 * 게임 전체 리뷰 화면의 시트 고르기(설계 2026-10-12 §2). 파일별로 묶은 체크 목록 — 전체, 파일 단위, 시트 단위.
 * 시트마다 행 수·확정 수, 그 시트의 첫 행으로 가는 버튼, 그 시트만 내보내는 버튼이 있다. 모두 고르면 "전체"(빈 목록)로 돌아간다.
 */
export function SheetPicker({ sheets, selected, onChange, onExport, exporting, onJump }: SheetPickerProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false) }
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); setOpen(false) } }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc, true)
    return () => { document.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onEsc, true) }
  }, [open])

  const all = sheets.filter((s): s is Pickable => s.configId != null)
  const allIds = all.map((s) => s.configId)
  const on = new Set(selected.length === 0 ? allIds : selected)
  // 결과가 전부면 "전체"(빈 목록), 하나도 없으면 바꾸지 않는다 — 빈 범위는 없다
  const commit = (next: Set<number>) => {
    if (next.size === 0) return
    onChange(next.size === allIds.length ? [] : allIds.filter((id) => next.has(id)))
  }
  const toggle = (ids: number[], value: boolean) => {
    const next = new Set(on)
    for (const id of ids) { if (value) next.add(id); else next.delete(id) }
    commit(next)
  }
  const files: { projectId: number; fileName: string; sheets: Pickable[] }[] = []
  for (const s of all) {
    const f = files.find((x) => x.projectId === s.projectId)
    if (f) f.sheets.push(s)
    else files.push({ projectId: s.projectId ?? 0, fileName: s.fileName ?? '', sheets: [s] })
  }

  return (
    <div ref={ref} className="relative mb-1">
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="dialog"
        className="press whitespace-nowrap rounded border border-line bg-white px-2 py-0.5 text-xs text-body hover:bg-canvas">
        시트 고르기 · {scopeLabel(sheets, selected)} <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <div role="dialog" aria-label="시트 고르기"
          className="pop-in absolute left-0 top-full z-40 mt-1 max-h-[70vh] w-[420px] overflow-y-auto rounded-xl border border-line bg-white p-2 text-xs shadow-xl">
          <label className="flex items-center gap-2 rounded px-2 py-1.5 font-semibold hover:bg-canvas">
            <input type="checkbox" checked={selected.length === 0} onChange={() => onChange([])} />
            전체 ({all.reduce((n, s) => n + s.total, 0).toLocaleString()}행)
          </label>
          {files.map((f) => {
            const ids = f.sheets.map((s) => s.configId)
            const every = ids.every((id) => on.has(id))
            return (
              <div key={f.projectId} className="mt-1 border-t border-lineSoft pt-1">
                <label className="flex items-center gap-2 rounded px-2 py-1 font-medium hover:bg-canvas">
                  <input type="checkbox" checked={every} onChange={() => toggle(ids, !every)} />
                  <span className="truncate" title={f.fileName}>{f.fileName}</span>
                </label>
                <ul>
                  {f.sheets.map((s) => (
                    <li key={s.configId} className="flex items-center gap-2 rounded py-0.5 pl-7 pr-2 hover:bg-canvas">
                      <label className="flex min-w-0 flex-1 items-center gap-2">
                        <input type="checkbox" checked={on.has(s.configId)} onChange={() => toggle([s.configId], !on.has(s.configId))} aria-label={`${f.fileName} ${s.sheet}`} />
                        <span className="truncate">{s.sheet}</span>
                        <span className="shrink-0 text-muted">{s.total.toLocaleString()}행 · 확정 {s.confirmed.toLocaleString()}</span>
                      </label>
                      <button type="button" onClick={() => { setOpen(false); onJump(s.configId) }} aria-label={`${f.fileName} ${s.sheet} 시작으로 이동`} title="이 시트의 첫 행으로 갑니다"
                        className="press shrink-0 rounded border border-line px-1.5 py-0.5 text-[11px] text-body hover:bg-white">
                        이동
                      </button>
                      <button type="button" disabled={exporting} onClick={() => onExport(s)} aria-label={`${s.sheet} 내보내기`} title="이 시트만 엑셀로 내보냅니다"
                        className="press shrink-0 rounded border border-line px-1.5 py-0.5 text-[11px] text-body hover:bg-white disabled:opacity-50">
                        내보내기
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
