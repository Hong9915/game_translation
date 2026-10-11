import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useDeferredValue, useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import type { CommandRow, OpSpec } from '../../api/types'
import { Button } from '../ui/Button'
import { CommandGuide, toolLabel } from './command/CommandGuide'
import { CheckRows, FixRows } from './command/CommandRows'
import { type CommandAsk, markCommandRunSeen, startCommandRun, useCommandRun } from './command/commandRuns'
import { OpsEditor } from './command/OpsEditor'

interface Props {
  projectId: number
  sheet: string
  /** 행을 고쳤을 때(그리드를 새로 읽게). 창은 열어 둔다. */
  onFixed: (applied: number) => void
  onClose: () => void
}

const TOOLS: { label: string; ask: CommandAsk }[] = [
  { label: '태그 띄어쓰기', ask: { ops: [{ kind: 'TAG_SPACING' }] } },
  { label: '조사 맞추기', ask: { ops: [{ kind: 'JOSA' }] } },
  { label: '같은 원문 통일', ask: { ops: [{ kind: 'UNIFY_SAME_SOURCE' }] } },
  { label: '태그 짝 검사', ask: { check: 'TAG_MISMATCH' } },
]

const input = 'rounded-lg border border-line bg-white px-3 py-1.5 text-[13px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/10'
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
/** 태그 짝을 깨는 행은 기본으로 빼 둔다. */
const skippedByDefault = (rows: CommandRow[]) => new Set(rows.filter((r) => r.warnings.some((w) => w.kind === 'TAG')).map((r) => r.segmentId))

/**
 * 리뷰 화면의 명령으로 고치기 창(설계 2026-10-08). 명령은 GPT가 도구로 시험해 작업 목록을 내고, 도구 버튼·저장 명령은 GPT 없이 바로
 * 작업 목록을 돌린다. 행마다 변경 전·후를 보이고 고른 행만 고친다. 태그 짝 검사는 검사 전용이라 "확인 필요로 표시"만 한다.
 */
export function CommandFixDialog({ projectId, sheet, onFixed, onClose }: Props) {
  const qc = useQueryClient()
  // 미리 보기 실행은 창 밖 보관소에 있다 — 창을 닫아도 끝까지 돌고, 다시 열면 결과가 그대로 있다. 이 시트의 실행만 본다.
  const run = useCommandRun(projectId)
  const mine = run && run.sheet === sheet ? run : null
  const plan = mine?.status === 'done' ? mine.plan ?? null : null
  const lastCommand = mine && 'command' in mine.ask ? mine.ask.command : ''
  const [command, setCommand] = useState(lastCommand)
  const commandRef = useRef<HTMLInputElement>(null)
  const [draft, setDraft] = useState<OpSpec[] | null>(null)
  const [editing, setEditing] = useState(false)
  const [skipped, setSkipped] = useState<ReadonlySet<number>>(new Set())
  const [done, setDone] = useState<ReadonlySet<number>>(new Set())
  const [search, setSearch] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [saving, setSaving] = useState<string | null>(null)
  const q = useDeferredValue(search).trim().toLowerCase()
  const presets = useQuery({ queryKey: ['command-presets', projectId], queryFn: () => api.workspace.commandPresets(projectId) })

  const left = (plan?.rows ?? []).filter((r) => !done.has(r.segmentId))
  const shown = left.filter((r) => !q || [r.src, r.before].some((t) => t.toLowerCase().includes(q)))
  const chosen = shown.filter((r) => !skipped.has(r.segmentId))
  const allChosen = shown.length > 0 && chosen.length === shown.length
  const opsEdited = plan?.kind === 'FIX' && draft !== null && !same(plan.ops, draft)

  // 새 미리 보기: 앞 목록의 고치기·표시·저장·삭제 오류를 지우고 보관소에서 실행한다(앞 결과는 실행이 바뀌며 사라진다).
  const ask = (a: CommandAsk) => {
    fix.reset(); mark.reset(); save.reset(); remove.reset(); setSaving(null)
    startCommandRun(projectId, sheet, a)
  }

  // 실행이 바뀌거나 끝나면(창이 닫혀 있다 다시 열린 때도) 작업 편집·고를 행을 그 결과로 맞추고, 본 것으로 표시한다.
  useEffect(() => {
    setDraft(plan?.ops ?? null); setEditing(false)
    setSkipped(skippedByDefault(plan?.rows ?? [])); setDone(new Set()); setNotice(null)
    if (mine) markCommandRunSeen(projectId)   // 다른 시트의 실행은 이 창에서 안 보이니 본 것으로 치지 않는다
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 실행(id)·상태가 바뀔 때만 맞춘다
  }, [mine?.id, mine?.status])

  const fix = useMutation({
    mutationFn: (items: CommandRow[]) => api.workspace.fixCommand(projectId, {
      sheet, command: lastCommand, ops: plan?.ops ?? [],
      items: items.map((r) => ({ segmentId: r.segmentId, before: r.before, after: r.after ?? '' })),
    }),
    onSuccess: (r, items) => {
      setDone((s) => new Set([...s, ...items.map((x) => x.segmentId)]))
      const missed = items.length - r.applied
      setNotice(`${r.applied.toLocaleString()}행을 고쳤습니다.` + (missed > 0 ? ` ${missed.toLocaleString()}행은 그 사이 번역이 바뀌었거나 확정돼 건너뛰었습니다.` : ''))
      onFixed(r.applied)
    },
  })

  const mark = useMutation({
    mutationFn: (items: CommandRow[]) => api.workspace.bulkMarks(projectId, { sheet, ids: items.map((r) => r.segmentId), needsCheck: true }),
    onSuccess: (_, items) => {
      setDone((s) => new Set([...s, ...items.map((x) => x.segmentId)]))
      setNotice(`${items.length.toLocaleString()}행을 확인 필요로 표시했습니다.`)
      onFixed(0)
    },
  })

  const save = useMutation({
    mutationFn: (name: string) => api.workspace.saveCommandPreset(projectId, { name, ops: plan?.ops ?? [] }),
    onSuccess: (p) => { setSaving(null); setNotice(`"${p.name}"(으)로 저장했습니다.`); qc.invalidateQueries({ queryKey: ['command-presets', projectId] }) },
  })

  const remove = useMutation({
    mutationFn: (presetId: number) => api.workspace.deleteCommandPreset(projectId, presetId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['command-presets', projectId] }),
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])

  const toggle = (id: number) => setSkipped((s) => { const n = new Set(s); if (!n.delete(id)) n.add(id); return n })
  const toggleAll = () => setSkipped((s) => {
    const n = new Set(s)
    for (const r of shown) { if (allChosen) n.add(r.segmentId); else n.delete(r.segmentId) }
    return n
  })
  const error = (mine?.status === 'error' ? mine.error : null) ?? [fix, mark, save, remove].find((m) => m.isError)?.error?.message ?? null
  const busy = mine?.status === 'running'

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="명령으로 고치기"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }} onKeyDown={(e) => e.stopPropagation()}>
      <div className="pop-in flex h-[88vh] w-[min(1200px,96vw)] flex-col rounded-2xl bg-white p-5 text-sm shadow-2xl">
        <h2 className="text-[15px] font-semibold text-ink">명령으로 고치기 · {sheet}</h2>
        <p className="mt-1 break-keep text-xs text-muted">
          할 일을 적으면 GPT가 시트에 시험해 보고 변경 전·후를 보여 줍니다. 고른 행만 고칩니다. 아래 버튼은 GPT 없이 바로 돌립니다.
        </p>
        <form className="mt-3 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (command.trim()) ask({ command: command.trim() }) }}>
          <input ref={commandRef} aria-label="명령" value={command} onChange={(e) => setCommand(e.target.value)} maxLength={300}
            placeholder="예: 구미호를 여우로 바꾸고 조사도 맞춰줘 / - 앞뒤 띄어쓰기 없애줘" className={`${input} min-w-0 flex-1`} autoFocus />
          <Button type="submit" variant="primary" disabled={!command.trim() || busy}>미리 보기</Button>
        </form>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          {TOOLS.map((t) => <Button key={t.label} disabled={busy} onClick={() => ask(t.ask)}>{t.label}</Button>)}
          {(presets.data ?? []).length > 0 && <span className="mx-1 h-4 w-px bg-line" />}
          {(presets.data ?? []).map((p) => (
            <Button key={p.id} disabled={busy} title="우클릭: 삭제" onClick={() => ask({ ops: p.ops })}
              onContextMenu={(e) => { e.preventDefault(); if (window.confirm(`"${p.name}"을(를) 지울까요?`)) remove.mutate(p.id) }}>{p.name}</Button>
          ))}
        </div>
        {plan && (
          <div className="mt-3 rounded-lg bg-canvas px-3 py-2">
            <div className="flex items-start gap-2">
              <p className="flex-1 text-[13px] font-medium text-ink">{plan.summary ?? (plan.kind === 'CHECK' ? '태그·자리표시자 짝이 원문과 다른 행입니다.' : null)}</p>
              {plan.kind === 'FIX' && (
                <div className="ml-auto flex shrink-0 gap-2">
                  {opsEdited && <Button disabled={busy || !draft?.length} onClick={() => draft && ask({ ops: draft })}>작업으로 다시 보기</Button>}
                  <Button onClick={() => setEditing((v) => !v)}>{editing ? '규칙 접기' : '규칙 고치기'}</Button>
                  {!opsEdited && <Button onClick={() => setSaving('')}>이 작업 저장</Button>}
                </div>
              )}
            </div>
            {plan.kind === 'FIX' && draft && <OpsEditor ops={draft} onChange={setDraft} editing={editing} />}
            {saving !== null && (
              <form className="mt-2 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (saving.trim()) save.mutate(saving.trim()) }}>
                <input aria-label="저장할 이름" value={saving} onChange={(e) => setSaving(e.target.value)} maxLength={40} className={`${input} w-64`} autoFocus />
                <Button type="submit" variant="primary" disabled={!saving.trim() || save.isPending}>저장</Button>
                <Button onClick={() => setSaving(null)}>취소</Button>
              </form>
            )}
            {plan.trail.length > 0 && (
              <details className="mt-2 text-xs text-muted">
                <summary className="cursor-pointer">GPT가 한 일 ({plan.trail.length})</summary>
                <ol className="mt-1 space-y-0.5 font-mono">
                  {plan.trail.map((t, i) => <li key={i} className="break-all" title={t.tool}><span className="font-sans font-medium text-ink">{toolLabel(t.tool)}</span> {t.args} → {t.result}</li>)}
                </ol>
              </details>
            )}
          </div>
        )}
        {plan && (
          <div className="mt-3 grid grid-cols-[1.5rem_3rem_1fr_1fr] items-center gap-x-3 border-b border-line pb-2 text-xs text-muted">
            <input type="checkbox" aria-label="보이는 행 전체 선택" checked={allChosen} onChange={toggleAll} disabled={shown.length === 0} />
            <span className="text-right">행</span>
            <span>{plan.kind === 'CHECK' ? '원문' : '변경 전'}</span>
            <span className="flex items-center gap-3">
              {plan.kind === 'CHECK' ? '번역' : '변경 후'}
              <span className="ml-auto tabular-nums">{chosen.length.toLocaleString()} / {shown.length.toLocaleString()}행 고름</span>
              <input aria-label="원문·번역 검색" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="원문·번역 검색"
                className={`${input} w-48 py-1`} />
            </span>
          </div>
        )}
        {notice && <p role="status" className="mt-2 rounded-lg bg-[#ecfdf5] px-3 py-2 text-[13px] text-[#065f46]">{notice}</p>}
        {error && <p role="alert" className="mt-2 text-[13px] text-orange">{error}</p>}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {busy ? <p className="py-12 text-center text-muted">{mine && 'command' in mine.ask ? 'GPT가 도구로 시험해 보는 중… (10~40초)' : '시트에 돌려 보는 중…'}</p>
            : !plan ? <CommandGuide onPick={(example) => { setCommand(example); commandRef.current?.focus() }} />
            : shown.length === 0 ? <p className="py-12 text-center text-muted">{plan.kind === 'CHECK' ? '짝이 안 맞는 행이 없습니다.' : '바뀌는 행이 없습니다.'}</p>
            : plan.kind === 'CHECK' ? <CheckRows rows={shown} skipped={skipped} onToggle={toggle} />
            : <FixRows rows={shown} skipped={skipped} onToggle={toggle} />}
        </div>
        <div className="mt-4 flex items-center justify-end gap-2">
          <Button onClick={onClose}>닫기</Button>
          {plan?.kind === 'CHECK' ? (
            <Button variant="primary" disabled={chosen.length === 0 || mark.isPending} onClick={() => { setNotice(null); mark.mutate(chosen) }}>
              {chosen.length.toLocaleString()}행 확인 필요로 표시
            </Button>
          ) : (
            <Button variant="primary" disabled={!plan || opsEdited || chosen.length === 0 || fix.isPending}
              title={opsEdited ? '손본 작업을 먼저 다시 보세요' : undefined}
              onClick={() => { setNotice(null); fix.mutate(chosen) }}>
              {chosen.length.toLocaleString()}행 고치기
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}
