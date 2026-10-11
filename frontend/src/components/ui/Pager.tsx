import { Button } from './Button'
import { inputClass } from './Field'

export const PAGE_SIZES = [25, 50, 100, 200] as const

/** 목록 상단의 페이지 크기 선택. 크기를 바꾸면 첫 페이지로 돌아간다. */
export function PageSize({ size, onSize, onPage }: { size: number; onSize: (s: number) => void; onPage: (p: number) => void }) {
  return (
    <div className="flex items-center justify-end border-t border-lineSoft px-3 py-2 text-[13px] text-muted first:border-t-0">
      <label className="flex items-center gap-1.5">한 번에
        <select aria-label="페이지 크기" className={`${inputClass} w-20 !py-1`} value={size} onChange={(e) => { onSize(Number(e.target.value)); onPage(0) }}>
          {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}행</option>)}
        </select>
      </label>
    </div>
  )
}

/** 목록 하단 페이지 이동. "1–50 / 3,881" + 이전/다음. */
export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  const from = total === 0 ? 0 : page * size + 1
  const to = Math.min(total, (page + 1) * size)
  return (
    <div className="flex items-center gap-3 px-3 py-2 text-[13px] text-muted">
      <span>{from.toLocaleString()}–{to.toLocaleString()} / {total.toLocaleString()}</span>
      <div className="flex-1" />
      <Button onClick={() => onPage(page - 1)} disabled={page <= 0} className="!px-2.5 !py-1 !text-xs">이전</Button>
      <span>{page + 1} / {pages}</span>
      <Button onClick={() => onPage(page + 1)} disabled={page + 1 >= pages} className="!px-2.5 !py-1 !text-xs">다음</Button>
    </div>
  )
}

/**
 * 표 위에 고정되는 목록 내비게이션: 페이지 크기 + 범위 + 이전/다음.
 * 스크롤해도 화면 상단에 남아 긴 표 중간에서도 바로 페이지를 옮길 수 있다.
 * total이 없으면(아직 안 읽혔으면) 페이지 크기만 보여 준다.
 */
export function ListNav({ page, size, total, onPage, onSize }: { page: number; size: number; total?: number; onPage: (p: number) => void; onSize: (s: number) => void }) {
  const pages = total == null ? 1 : Math.max(1, Math.ceil(total / size))
  const from = !total ? 0 : page * size + 1
  const to = total == null ? 0 : Math.min(total, (page + 1) * size)
  return (
    <div className="sticky top-0 z-10 flex items-center gap-3 border-y border-lineSoft bg-white/95 px-3 py-2 text-[13px] text-muted backdrop-blur">
      <label className="flex items-center gap-1.5">한 번에
        <select aria-label="페이지 크기" className={`${inputClass} w-20 !py-1`} value={size} onChange={(e) => { onSize(Number(e.target.value)); onPage(0) }}>
          {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}행</option>)}
        </select>
      </label>
      <div className="flex-1" />
      {total != null && <>
        <span>{from.toLocaleString()}–{to.toLocaleString()} / {total.toLocaleString()}</span>
        <Button onClick={() => onPage(page - 1)} disabled={page <= 0} className="!px-2.5 !py-1 !text-xs">이전</Button>
        <span>{page + 1} / {pages}</span>
        <Button onClick={() => onPage(page + 1)} disabled={page + 1 >= pages} className="!px-2.5 !py-1 !text-xs">다음</Button>
      </>}
    </div>
  )
}
