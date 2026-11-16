import { useEffect, useRef, useState } from 'react'

export interface MenuItem { label: string; onSelect: () => void; danger?: boolean }

/** 파일 줄 끝의 ⋯ 메뉴. 바깥을 누르거나 Esc면 닫힌다. 단추 아래 오른쪽 끝에 붙어 단추에서 자라난다. */
export function FileRowMenu({ label, items }: { label: string; items: MenuItem[] }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const down = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', down)
    document.addEventListener('keydown', key)
    return () => { document.removeEventListener('mousedown', down); document.removeEventListener('keydown', key) }
  }, [open])
  return (
    <div ref={box} className="relative">
      <button type="button" aria-label={label} title={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}
        className="press flex h-9 w-9 items-center justify-center rounded-lg text-body hover:bg-canvas hover:text-ink">
        <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg>
      </button>
      {open && (
        <div role="menu" className="absolute right-0 top-full z-30 mt-1 min-w-36 origin-top-right pop-in rounded-lg border border-line bg-white py-1 shadow-[0_8px_24px_-8px_rgba(16,24,40,0.25)]">
          {items.map((it) => (
            <button key={it.label} type="button" role="menuitem" onClick={() => { setOpen(false); it.onSelect() }}
              className={`block w-full px-3 py-2 text-left text-[13px] hover:bg-canvas ${it.danger ? 'text-[#c0392b]' : 'text-ink'}`}>
              {it.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
