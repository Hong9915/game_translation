import type { CommandRow, OpKind } from '../../../api/types'
import { textDiff } from '../../../lib/textDiff'

const BY: Record<OpKind, string> = { REPLACE: '찾아 바꾸기', TAG_SPACING: '태그', JOSA: '조사', UNIFY_SAME_SOURCE: '통일' }
const visible = (t: string) => t.replace(/ /g, '␣')
const grid = 'grid grid-cols-[1.5rem_3rem_1fr_1fr] items-start gap-x-3'

interface Props { rows: CommandRow[]; skipped: ReadonlySet<number>; onToggle: (segmentId: number) => void }

function Side({ before, after, side }: { before: string; after: string; side: 'before' | 'after' }) {
  return (
    <span data-testid={side} className="whitespace-pre-wrap break-all">
      {textDiff(before, after).map((p, i) => p.kind === 'same' ? <span key={i}>{p.text}</span>
        : p.kind === 'removed' ? (side === 'before' ? <del key={i} title="지우는 글자" className="rounded-sm bg-[#fee2e2] text-[#b91c1c]">{visible(p.text)}</del> : null)
        : (side === 'after' ? <ins key={i} title="더하는 글자" className="rounded-sm bg-[#bbf7d0] no-underline">{visible(p.text)}</ins> : null))}
    </span>
  )
}

/** 고칠 후보: 변경 전·후 두 칸, 바꾼 작업 배지, 경고(주황). */
export function FixRows({ rows, skipped, onToggle }: Props) {
  return (
    <ul>
      {rows.map((r) => (
        <li key={r.segmentId} aria-label={r.after ?? ''} className="border-b border-lineSoft py-2">
          <label className={`${grid} cursor-pointer`}>
            <input type="checkbox" className="mt-1" aria-label={r.after ?? ''} checked={!skipped.has(r.segmentId)} onChange={() => onToggle(r.segmentId)} />
            <span className="pt-px text-right text-xs tabular-nums text-muted">{r.rowNo}행</span>
            <span className="min-w-0 text-[14px] text-ink"><Side before={r.before} after={r.after ?? ''} side="before" /></span>
            <span className="min-w-0 text-[14px] text-ink"><Side before={r.before} after={r.after ?? ''} side="after" /></span>
            <span className="col-start-3 col-end-5 mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-muted">
              {r.by.map((k) => <span key={k} className="rounded bg-canvas px-1.5 py-px text-[11px] text-body">{BY[k]}</span>)}
              {r.warnings.map((w, i) => (
                <span key={i} className="rounded bg-[#fff7ed] px-1.5 py-px text-[11px] text-orange">{w.kind === 'JOSA' ? `조사: ${w.text}` : w.text}</span>
              ))}
              <span className="truncate">원문 {r.src}</span>
            </span>
          </label>
        </li>
      ))}
    </ul>
  )
}

/** 검사 전용(태그 짝): 원문·번역 두 칸, 짝 안 맞는 토큰을 칠한다. 체크는 "확인 필요로 표시" 대상. */
export function CheckRows({ rows, skipped, onToggle }: Props) {
  return (
    <ul>
      {rows.map((r) => (
        <li key={r.segmentId} aria-label={`${r.rowNo}행`} className="border-b border-lineSoft py-2">
          <label className={`${grid} cursor-pointer`}>
            <input type="checkbox" className="mt-1" aria-label={`${r.rowNo}행 표시`} checked={!skipped.has(r.segmentId)} onChange={() => onToggle(r.segmentId)} />
            <span className="pt-px text-right text-xs tabular-nums text-muted">{r.rowNo}행</span>
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
