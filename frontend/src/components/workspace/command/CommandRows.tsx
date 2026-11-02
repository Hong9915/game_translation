import { useEffect, useMemo, useRef, useState } from 'react'
import type { CommandRow, OpKind } from '../../../api/types'
import { lengthCount } from '../../../lib/lengthCheck'
import { textDiff } from '../../../lib/textDiff'

const BY: Record<OpKind, string> = { REPLACE: '찾아 바꾸기', TAG_SPACING: '태그', JOSA: '조사', UNIFY_SAME_SOURCE: '통일', SHORTEN: '길이', TERM: '용어' }
const visible = (t: string) => t.replace(/ /g, '␣')
const grid = 'grid grid-cols-[1.5rem_3rem_1fr_1fr] items-start gap-x-3'

/** showSheet: 게임 화면이면 행 번호 앞에 시트 이름을 붙인다(여러 시트가 섞여 있다). */
/** onSetMany(ids, skip): 묶음 체크 — ids를 모두 빼거나(skip) 모두 고른다. */
interface Props { rows: CommandRow[]; skipped: ReadonlySet<number>; onToggle: (segmentId: number) => void; onSetMany: (ids: number[], skip: boolean) => void; showSheet?: boolean }
const where = (r: CommandRow, showSheet?: boolean) => `${showSheet && r.sheet ? `${r.sheet} ` : ''}${r.rowNo}행`

function Side({ before, after, side }: { before: string; after: string; side: 'before' | 'after' }) {
  return (
    <span data-testid={side} className="whitespace-pre-wrap break-all">
      {textDiff(before, after).map((p, i) => p.kind === 'same' ? <span key={i}>{p.text}</span>
        : p.kind === 'removed' ? (side === 'before' ? <del key={i} title="지우는 글자" className="rounded-sm bg-[#fee2e2] text-[#b91c1c]">{visible(p.text)}</del> : null)
        : (side === 'after' ? <ins key={i} title="더하는 글자" className="rounded-sm bg-[#bbf7d0] no-underline">{visible(p.text)}</ins> : null))}
    </span>
  )
}

/** 길이 줄이기 비교: 글자 수 전 → 후와 목표. 목표를 넘으면 주황(경고 배지도 따로 붙는다). */
function Length({ before, after, target }: { before: string; after: string; target: number }) {
  const n = lengthCount(after)
  return (
    <span data-testid="length" className="rounded bg-canvas px-1.5 py-px text-[11px] tabular-nums text-body">
      {lengthCount(before)} → <b className={n > target ? 'text-orange' : 'text-[#15803d]'}>{n}자</b> (목표 {target})
    </span>
  )
}

/** 숫자를 #으로 접은 꼴 — 원문·변경 전·변경 후가 이 꼴로 같으면 숫자만 다른 행이라 한 줄로 묶는다(서버 용어 바꾸기의 묶음과 같은 기준). */
const shape = (t: string) => t.replace(/[0-9]+/g, '#')
interface Group { key: string; rows: CommandRow[] }
function groupRows(rows: CommandRow[]): Group[] {
  const byKey = new Map<string, Group>()
  for (const r of rows) {
    const key = `${shape(r.src)}\u0001${shape(r.before)}\u0001${shape(r.after ?? '')}`
    const g = byKey.get(key)
    if (g) g.rows.push(r); else byKey.set(key, { key, rows: [r] })
  }
  return [...byKey.values()]
}
/** 펼친 묶음에서 한 번에 그리는 행 수. 수천 행을 한꺼번에 그리면 창이 굳는다. */
const PAGE = 200

/** 고칠 후보 한 행: 변경 전·후 두 칸, 바꾼 작업 배지, 경고(주황). */
function FixRow({ r, checked, onToggle, showSheet, nested }: { r: CommandRow; checked: boolean; onToggle: () => void; showSheet?: boolean; nested?: boolean }) {
  return (
    <li aria-label={r.after ?? ''} className={`border-b border-lineSoft py-2 ${nested ? 'bg-canvas/60 pl-6' : ''}`}>
      <label className={`${grid} cursor-pointer`}>
        <input type="checkbox" className="mt-1" aria-label={r.after ?? ''} checked={checked} onChange={onToggle} />
        <span className="pt-px text-right text-xs tabular-nums text-muted">{where(r, showSheet)}</span>
        <span className="min-w-0 text-[14px] text-ink"><Side before={r.before} after={r.after ?? ''} side="before" /></span>
        <span className="min-w-0 text-[14px] text-ink"><Side before={r.before} after={r.after ?? ''} side="after" /></span>
        <span className="col-start-3 col-end-5 mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          {r.by.map((k) => <span key={k} className="rounded bg-canvas px-1.5 py-px text-[11px] text-body">{BY[k]}</span>)}
          {r.target != null && <Length before={r.before} after={r.after ?? ''} target={r.target} />}
          {r.warnings.filter((w) => w.kind !== 'LENGTH').map((w, i) => (
            <span key={i} className="rounded bg-[#fff7ed] px-1.5 py-px text-[11px] text-orange">{w.kind === 'JOSA' ? `조사: ${w.text}` : w.text}</span>
          ))}
          <span className="truncate">원문 {r.src}</span>
        </span>
      </label>
    </li>
  )
}

/** 숫자만 다른 행 묶음: 대표 한 행과 ×N. 묶음 체크는 안의 행 전체를 켜고 끈다. 펼치면 안의 행을 하나씩 고른다. */
function FixGroup({ g, skipped, onToggle, onSetMany, showSheet }: { g: Group } & Omit<Props, 'rows'>) {
  const [open, setOpen] = useState(false)
  const [limit, setLimit] = useState(PAGE)
  const ids = g.rows.map((r) => r.segmentId)
  const on = ids.filter((id) => !skipped.has(id)).length
  const box = useRef<HTMLInputElement>(null)
  useEffect(() => { if (box.current) box.current.indeterminate = on > 0 && on < ids.length }, [on, ids.length])
  const r = g.rows[0]
  return (
    <li aria-label={`묶음 ${r.after ?? ''}`} className="border-b border-lineSoft py-2">
      <div className={grid}>
        <input ref={box} type="checkbox" className="mt-1" aria-label={`묶음 ${r.after ?? ''} ${ids.length}행`} checked={on === ids.length}
          onChange={() => onSetMany(ids, on === ids.length)} />
        <span className="pt-px text-right text-xs font-semibold tabular-nums text-primary">×{ids.length.toLocaleString()}</span>
        <span className="min-w-0 text-[14px] text-ink"><Side before={r.before} after={r.after ?? ''} side="before" /></span>
        <span className="min-w-0 text-[14px] text-ink"><Side before={r.before} after={r.after ?? ''} side="after" /></span>
        <span className="col-start-3 col-end-5 mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          {r.by.map((k) => <span key={k} className="rounded bg-canvas px-1.5 py-px text-[11px] text-body">{BY[k]}</span>)}
          <span>숫자만 다른 행 {ids.length.toLocaleString()}개{on < ids.length ? ` · ${on.toLocaleString()}개 고름` : ''}</span>
          <button type="button" className="text-primary hover:underline" onClick={() => setOpen((v) => !v)}>{open ? '접기' : '펼치기'}</button>
          <span className="truncate">원문 {r.src}</span>
        </span>
      </div>
      {open && (
        <ul className="mt-2">
          {g.rows.slice(0, limit).map((x) => (
            <FixRow key={x.segmentId} r={x} nested showSheet={showSheet} checked={!skipped.has(x.segmentId)} onToggle={() => onToggle(x.segmentId)} />
          ))}
          {g.rows.length > limit && (
            <li className="py-2 pl-6">
              <button type="button" className="text-xs text-primary hover:underline" onClick={() => setLimit((n) => n + PAGE)}>
                {Math.min(PAGE, g.rows.length - limit).toLocaleString()}행 더 보기 ({(g.rows.length - limit).toLocaleString()}행 남음)
              </button>
            </li>
          )}
        </ul>
      )}
    </li>
  )
}

/** 고칠 후보. 숫자만 다른 행은 한 줄로 묶는다. */
export function FixRows({ rows, skipped, onToggle, onSetMany, showSheet }: Props) {
  const groups = useMemo(() => groupRows(rows), [rows])
  return (
    <ul>
      {groups.map((g) => g.rows.length > 1
        ? <FixGroup key={g.key} g={g} skipped={skipped} onToggle={onToggle} onSetMany={onSetMany} showSheet={showSheet} />
        : <FixRow key={g.rows[0].segmentId} r={g.rows[0]} showSheet={showSheet} checked={!skipped.has(g.rows[0].segmentId)} onToggle={() => onToggle(g.rows[0].segmentId)} />)}
    </ul>
  )
}

/** 검사 전용(태그 짝): 원문·번역 두 칸, 짝 안 맞는 토큰을 칠한다. 체크는 "확인 필요로 표시" 대상. */
export function CheckRows({ rows, skipped, onToggle, showSheet }: Props) {
  return (
    <ul>
      {rows.map((r) => (
        <li key={r.segmentId} aria-label={`${r.rowNo}행`} className="border-b border-lineSoft py-2">
          <label className={`${grid} cursor-pointer`}>
            <input type="checkbox" className="mt-1" aria-label={`${r.rowNo}행 표시`} checked={!skipped.has(r.segmentId)} onChange={() => onToggle(r.segmentId)} />
            <span className="pt-px text-right text-xs tabular-nums text-muted">{where(r, showSheet)}</span>
            <span className="min-w-0 whitespace-pre-wrap break-all text-[14px] text-ink">{r.src}</span>
            <span className="min-w-0 whitespace-pre-wrap break-all text-[14px] text-ink">{r.before}</span>
            <span className="col-start-3 col-end-5 mt-0.5 flex flex-wrap gap-1.5 text-xs">
              {r.mismatch?.src.map((t, i) => <code key={`s${i}`} title="번역에 없음" className="rounded bg-[#fee2e2] px-1 text-[#b91c1c]">{t}</code>)}
              {r.mismatch?.ko.map((t, i) => <code key={`k${i}`} title="원문에 없음" className="rounded bg-[#fff7ed] px-1 text-orange">{t}</code>)}
            </span>
          </label>
        </li>
      ))}
    </ul>
  )
}
