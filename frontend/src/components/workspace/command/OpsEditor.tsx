import type { CommandStep, OpSpec } from '../../../api/types'

const NUM = ['①', '②', '③', '④', '⑤', '⑥']
const NAME: Record<OpSpec['kind'], string> = { REPLACE: '찾아 바꾸기', TAG_SPACING: '태그 띄어쓰기', JOSA: '조사 맞추기', UNIFY_SAME_SOURCE: '같은 원문 통일' }

/** 칩 글: 찾아 바꾸기는 조건까지. */
export function opLabel(op: OpSpec): string {
  if (op.kind !== 'REPLACE' || !op.filter?.contains) return NAME[op.kind]
  return `${NAME.REPLACE} · ${op.filter.in === 'src' ? '원문' : '번역'}에 “${op.filter.contains}”가 든 행`
}

const visible = (t: string) => t.replace(/ /g, '␣')
const input = 'rounded-lg border border-line bg-white px-3 py-1.5 text-[13px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/10'
const code = 'rounded bg-white px-1.5 py-0.5 font-mono text-xs text-ink ring-1 ring-line'

interface Props {
  ops: OpSpec[]
  onChange: (ops: OpSpec[]) => void
  /** 참이면 찾아 바꾸기 조건·단계를 고칠 수 있다. */
  editing: boolean
}

/** 작업 목록(설계 2026-10-08 §6): 차례 칩과 빼기, 찾아 바꾸기 단계 보기·고치기. */
export function OpsEditor({ ops, onChange, editing }: Props) {
  const setOp = (i: number, op: OpSpec) => onChange(ops.map((o, k) => (k === i ? op : o)))
  return (
    <ol className="mt-1.5 space-y-1.5 text-[13px] text-body">
      {ops.map((op, i) => (
        <li key={i}>
          <div className="flex items-center gap-1.5">
            <span className="font-medium text-ink">{NUM[i]} {opLabel(op)}</span>
            {ops.length > 1 && (
              <button type="button" aria-label={`${NAME[op.kind]} 빼기`} className="px-1 text-muted hover:text-ink"
                onClick={() => onChange(ops.filter((_, k) => k !== i))}>×</button>
            )}
          </div>
          {op.kind === 'REPLACE' && (editing ? <ReplaceEditor op={op} onChange={(o) => setOp(i, o)} /> : <Steps steps={op.steps ?? []} />)}
        </li>
      ))}
    </ol>
  )
}

function Steps({ steps }: { steps: CommandStep[] }) {
  return (
    <ol className="ml-5 mt-1 space-y-1">
      {steps.map((s, i) => (
        <li key={i} className="flex flex-wrap items-center gap-1.5">
          {s.regex && <span className="text-xs text-muted">정규식</span>}
          <code className={code}>{visible(s.find)}</code><span className="text-muted">→</span>
          {s.replace ? <code className={code}>{visible(s.replace)}</code> : <span className="text-xs text-muted">(지움)</span>}
        </li>
      ))}
    </ol>
  )
}

function ReplaceEditor({ op, onChange }: { op: OpSpec; onChange: (o: OpSpec) => void }) {
  const steps = op.steps ?? []
  const setStep = (i: number, patch: Partial<CommandStep>) => onChange({ ...op, steps: steps.map((s, k) => (k === i ? { ...s, ...patch } : s)) })
  return (
    <div className="ml-5 mt-1.5 space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <select aria-label="조건 대상" value={op.filter?.in ?? 'ko'} className={input}
          onChange={(e) => onChange({ ...op, filter: { in: e.target.value as 'ko' | 'src', contains: op.filter?.contains ?? '' } })}>
          <option value="ko">번역</option><option value="src">원문</option>
        </select>
        에
        <input aria-label="조건 낱말" value={op.filter?.contains ?? ''} placeholder="(비우면 모든 행)" className={`${input} w-48`}
          onChange={(e) => onChange({ ...op, filter: e.target.value ? { in: op.filter?.in ?? 'ko', contains: e.target.value } : null })} />
        가 든 행만
      </div>
      {steps.map((s, i) => (
        <div key={i} className="flex flex-wrap items-center gap-2">
          <span className="w-10 text-xs text-muted">{i + 1}단계</span>
          <input aria-label={`${i + 1}단계 찾을 것`} value={s.find} onChange={(e) => setStep(i, { find: e.target.value })} spellCheck={false}
            className={`${input} min-w-0 flex-[3] font-mono text-xs`} />
          <span className="text-xs text-muted">→</span>
          <input aria-label={`${i + 1}단계 바꿀 꼴`} value={s.replace} onChange={(e) => setStep(i, { replace: e.target.value })} spellCheck={false}
            className={`${input} min-w-0 flex-[2] font-mono text-xs`} />
          <label className="flex items-center gap-1 text-xs text-muted">
            <input type="checkbox" checked={s.regex} onChange={(e) => setStep(i, { regex: e.target.checked })} />정규식
          </label>
          {steps.length > 1 && (
            <button type="button" aria-label={`${i + 1}단계 빼기`} className="px-1 text-muted hover:text-ink"
              onClick={() => onChange({ ...op, steps: steps.filter((_, k) => k !== i) })}>×</button>
          )}
        </div>
      ))}
      <button type="button" className="text-xs text-primary hover:underline"
        onClick={() => onChange({ ...op, steps: [...steps, { find: '', replace: '', regex: false }] })}>+ 단계 더하기</button>
    </div>
  )
}
