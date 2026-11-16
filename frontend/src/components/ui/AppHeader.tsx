import { Fragment, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

export interface Crumb { label: string; to?: string }

/** 게임 목록·게임·정규화 화면이 같이 쓰는 위쪽 줄. 왼쪽은 앱 표시와 위치(게임 › 파일), 오른쪽은 화면별 내용. */
export function AppHeader({ crumbs = [], right }: { crumbs?: Crumb[]; right?: ReactNode }) {
  return (
    <header className="sticky top-0 z-20 flex h-14 shrink-0 items-center justify-between gap-4 border-b border-line/70 bg-white/90 px-6 backdrop-blur">
      <nav aria-label="위치" className="flex min-w-0 items-center gap-2 text-[13px]">
        <Link to="/" className="flex shrink-0 items-center gap-2.5 font-semibold text-ink">
          <span aria-hidden className="flex h-6 w-6 items-center justify-center rounded-md bg-primary text-xs font-bold text-white">번</span>
          {crumbs.length === 0 ? <span className="text-[15px]">게임 번역 검수</span> : <span className="font-normal text-body hover:text-ink">게임</span>}
        </Link>
        {crumbs.map((c, i) => (
          <Fragment key={i}>
            <span aria-hidden className="text-muted">›</span>
            {c.to
              ? <Link to={c.to} className="truncate text-body hover:text-ink">{c.label}</Link>
              : <span aria-current="page" className="truncate font-semibold text-ink">{c.label}</span>}
          </Fragment>
        ))}
      </nav>
      {right && <div className="flex shrink-0 items-center gap-3">{right}</div>}
    </header>
  )
}

/** 확정 진행 막대. total이 0이면(아직 정규화 전) 막대 대신 —. */
export function Progress({ done, total, showCount = false }: { done: number; total: number; showCount?: boolean }) {
  if (total <= 0) return <span className="text-xs text-muted">—</span>
  const pct = Math.min(100, Math.round((done / total) * 100))
  return (
    <div className="flex items-center gap-2.5">
      <div role="progressbar" aria-label="확정 진행" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done}
        className="h-1.5 flex-1 overflow-hidden rounded-full bg-lineSoft">
        <div className="h-full rounded-full bg-green transition-[width] duration-300 motion-reduce:transition-none" style={{ width: `${pct}%` }} />
      </div>
      <span className="shrink-0 text-right text-xs tabular-nums text-body">
        {showCount ? `${done.toLocaleString()} / ${total.toLocaleString()}` : `${pct}%`}
      </span>
    </div>
  )
}
