import { useEffect, useState } from 'react'

/** 검수 그리드 보기: 시트 전체를 스크롤하거나, 50·100행씩 쪽을 넘긴다. */
export type ViewSize = 'all' | 50 | 100

const SIZES: { value: ViewSize; label: string }[] = [{ value: 'all', label: '전체' }, { value: 50, label: '50행' }, { value: 100, label: '100행' }]

/** 보기 선택 버튼 묶음. 시트 바 오른쪽에 둔다. */
export function ViewSizePicker({ value, onChange }: { value: ViewSize; onChange: (v: ViewSize) => void }) {
  return (
    <div role="group" aria-label="보기" className="mb-1 flex items-center gap-1 text-xs text-muted">
      보기
      <div className="flex overflow-hidden rounded border border-line">
        {SIZES.map((s) => (
          <button key={s.label} type="button" aria-pressed={value === s.value} onClick={() => onChange(s.value)}
            className={`press px-1.5 py-0.5 ${value === s.value ? 'bg-ink font-semibold text-white' : 'bg-white text-body hover:bg-canvas'}`}>
            {s.label}
          </button>
        ))}
      </div>
    </div>
  )
}

interface PageBarProps { page: number; size: number; total: number; onPage: (page: number) => void }

/** 쪽 넘기기. 표 위 도구줄(보기 선택 옆)에 들어간다. page는 0부터, 화면에는 1부터 보인다. */
export function PageBar({ page, size, total, onPage }: PageBarProps) {
  const pages = Math.max(1, Math.ceil(total / size))
  const [draft, setDraft] = useState(String(page + 1))
  useEffect(() => { setDraft(String(page + 1)) }, [page])
  const go = () => {
    const n = Number.parseInt(draft, 10)
    if (Number.isNaN(n)) { setDraft(String(page + 1)); return }
    const next = Math.min(pages, Math.max(1, n)) - 1
    setDraft(String(next + 1))
    if (next !== page) onPage(next)
  }
  const from = total === 0 ? 0 : page * size + 1
  const to = Math.min(total, (page + 1) * size)
  const btn = 'press rounded border border-line px-1.5 py-0.5 hover:bg-canvas disabled:opacity-40 disabled:hover:bg-transparent'
  return (
    <div role="group" aria-label="쪽 이동" className="mb-1 flex items-center gap-1 text-xs text-body">
      <button type="button" aria-label="처음 쪽" className={btn} disabled={page === 0} onClick={() => onPage(0)}>«</button>
      <button type="button" aria-label="이전 쪽" className={btn} disabled={page === 0} onClick={() => onPage(page - 1)}>‹</button>
      <input type="text" inputMode="numeric" aria-label="쪽 번호" value={draft} onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') go() }} onBlur={() => setDraft(String(page + 1))}
        className="w-14 rounded border border-line px-1 py-0.5 text-center" />
      <span>/ {pages.toLocaleString()}</span>
      <button type="button" aria-label="다음 쪽" className={btn} disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>›</button>
      <button type="button" aria-label="마지막 쪽" className={btn} disabled={page >= pages - 1} onClick={() => onPage(pages - 1)}>»</button>
      <span className="ml-1.5 whitespace-nowrap text-muted">{total === 0 ? '0' : `${from.toLocaleString()}–${to.toLocaleString()}`} / {total.toLocaleString()}행</span>
    </div>
  )
}
