import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import type { CorrectionRule, RulePreviewRow } from '../../api/types'
import { Button } from '../ui/Button'

/** 앞뒤 같은 부분을 빼고 가운데 바뀐 곳만 강조한다. */
function Changed({ before, after }: { before: string; after: string }) {
  let s = 0
  while (s < before.length && s < after.length && before[s] === after[s]) s++
  let e = 0
  while (e < before.length - s && e < after.length - s && before[before.length - 1 - e] === after[after.length - 1 - e]) e++
  const mid = after.slice(s, after.length - e)
  return (
    <span className="whitespace-pre-wrap break-all">
      {after.slice(0, s)}{mid && <mark className="rounded-sm bg-[#bbf7d0] px-px text-inherit">{mid}</mark>}{after.slice(after.length - e)}
    </span>
  )
}

const label = (r: RulePreviewRow) => `${r.fileName ?? ''} ${r.sheet ?? ''} ${r.rowNo}행`

/**
 * 문체 규칙 하나의 미리 보기(설계 2026-11-13 §5·§6). 같은 글은 한 줄로 묶고, 끈 줄은 적용할 때 "뺀 행"으로 남는다.
 * 기계 변환이 안 된 행은 따로 모아 GPT로 고칠 수 있다.
 */
export function RulePreview({ gameId, rule, title, onClose }: { gameId: number; rule: CorrectionRule; title: string; onClose: () => void }) {
  const qc = useQueryClient()
  const preview = useQuery({ queryKey: ['rulePreview', rule.id], queryFn: () => api.rules.preview(rule.id) })
  const [rows, setRows] = useState<RulePreviewRow[]>([])
  const [checked, setChecked] = useState<ReadonlySet<number>>(new Set())
  // 사람이 직접 끈 행만 "뺀 행"으로 보낸다 — 처음부터 꺼진 행(기계 변환 안 됨·바뀌는 게 없는 행)은 빼지 않는다(설계 §6)
  const [userOff, setUserOff] = useState<ReadonlySet<number>>(new Set())
  const [file, setFile] = useState('')
  const [result, setResult] = useState<string | null>(null)
  /** 직접 고치는 중인 묶음의 행들. 고친 글은 그 행들 모두에 들어가고, 기계 변환이 안 됐던 행도 고치면 켜진다. */
  const [editing, setEditing] = useState<number[] | null>(null)
  const setAfter = (ids: number[], text: string) => {
    const set = new Set(ids)
    setRows((prev) => prev.map((r) => (set.has(r.segmentId) ? { ...r, after: text, complete: true } : r)))
    setChecked((prev) => new Set([...prev, ...ids]))
    setUserOff((prev) => { const next = new Set(prev); for (const id of ids) next.delete(id); return next })
  }

  useEffect(() => {
    if (!preview.data) return
    setRows(preview.data.rows)
    setChecked(new Set(preview.data.rows.filter((r) => r.checked).map((r) => r.segmentId)))
    setUserOff(new Set())
  }, [preview.data])

  const files = useMemo(() => [...new Set(rows.map((r) => r.fileName ?? ''))].sort(), [rows])
  const shown = rows.filter((r) => !file || r.fileName === file)
  const ready = shown.filter((r) => r.complete)
  const incomplete = shown.filter((r) => !r.complete)

  // 한 행이라도 빼면 단어장 넣기를 끈다 — 넣으면 나중 단어장 적용 때 뺀 행도 바뀔 수 있다(설계 §6)
  /** 묶음(같은 글의 행들)을 한꺼번에 켜고 끈다. 하나라도 켜져 있으면 끈다. */
  const toggle = (ids: number[]) => {
    const off = ids.some((id) => checked.has(id))
    const apply = (prev: ReadonlySet<number>, add: boolean) => { const next = new Set(prev); for (const id of ids) { if (add) next.add(id); else next.delete(id) } return next }
    setChecked((prev) => apply(prev, !off))
    setUserOff((prev) => apply(prev, off))
  }
  const willApply = shown.filter((r) => checked.has(r.segmentId))
  // 전체 선택은 보이는(파일로 거른) 기계 변환된 행만 켠다. 전체 해제는 "뺀 행"으로 남기지 않는다 —
  // 다 끄고 몇 개만 골라 적용해도 나머지는 다음에 다시 추천된다(2026-11-16)
  const allOn = ready.length > 0 && ready.every((r) => checked.has(r.segmentId))
  const someOn = shown.some((r) => checked.has(r.segmentId))
  const toggleAll = () => {
    const ids = (allOn ? shown : ready).map((r) => r.segmentId)
    setChecked((prev) => { const next = new Set(prev); for (const id of ids) { if (allOn) next.delete(id); else next.add(id) } return next })
    setUserOff((prev) => { const next = new Set(prev); for (const id of ids) next.delete(id); return next })
  }


  const rewrite = useMutation({
    mutationFn: () => api.rules.rewrite(rule.id, incomplete.map((r) => r.segmentId).slice(0, 200)),
    onSuccess: (fixed) => {
      const byId = new Map(fixed.map((r) => [r.segmentId, r]))
      setRows((prev) => prev.map((r) => byId.get(r.segmentId) ?? r))
      setChecked((prev) => new Set([...prev, ...fixed.map((r) => r.segmentId)]))
    },
  })

  const apply = useMutation({
    mutationFn: () => {
      // 파일로 걸렀으면 보이는 행만 — 숨은 행은 적용도, 빼기도 하지 않는다
      return api.rules.apply(rule.id, {
        checked: willApply.map((r) => ({ segmentId: r.segmentId, before: r.before, after: r.after })),
        unchecked: shown.filter((r) => userOff.has(r.segmentId)).map((r) => r.segmentId)
      })
    },
    onSuccess: (res) => {
      setResult(`${res.applied}행 적용` + (res.skippedChanged ? `, ${res.skippedChanged}행은 그새 바뀌어 건너뜀` : ''))
      void qc.invalidateQueries({ queryKey: ['rules', gameId] })
      void qc.invalidateQueries({ queryKey: ['rulePreview', rule.id] })
    },
  })

  // 지금 글과 바뀐 글이 같은 행은 한 줄로 묶어 보여 준다(같은 문구가 여러 파일·행에 반복되는 경우가 많다)
  const group = (list: RulePreviewRow[]) => {
    const m = new Map<string, RulePreviewRow[]>()
    for (const r of list) { const k = `${r.before}\u0000${r.after}`; const g = m.get(k); if (g) g.push(r); else m.set(k, [r]) }
    return [...m.values()]
  }
  const row = (rs: RulePreviewRow[]) => {
    const r = rs[0]
    const ids = rs.map((x) => x.segmentId)
    const name = rs.length > 1 ? `${label(r)} 외 ${rs.length - 1}행` : label(r)
    return (
    <li key={r.segmentId} className="flex gap-3 border-b border-line py-2">
      <input type="checkbox" aria-label={name} checked={ids.every((id) => checked.has(id))} onChange={() => toggle(ids)} className="mt-1" />
      <div className="min-w-0 flex-1">
        <div className="text-xs text-muted" title={rs.map(label).join('\n')}>
          {name} · {r.src}
          {rs.length > 1 && <span className="ml-2 rounded bg-primarySoft px-1.5 font-semibold text-primary">×{rs.length}</span>}
        </div>
        <div className="text-body line-through decoration-muted/50">{r.before}</div>
        {editing?.[0] === r.segmentId ? (
          <div className="mt-1 flex items-start gap-2">
            <textarea aria-label="바뀐 글" autoFocus value={r.after} onChange={(e) => setAfter(ids, e.target.value)} rows={2}
              className="min-w-0 flex-1 rounded-lg border border-primary px-2 py-1 text-ink outline-none focus:ring-4 focus:ring-primary/10" />
            <Button onClick={() => setEditing(null)}>완료</Button>
          </div>
        ) : (
          <div className="flex items-start gap-2 text-ink">
            <span className="min-w-0 flex-1"><Changed before={r.before} after={r.after} /></span>
            <button type="button" aria-label={`${name} 수정`} title="바뀐 글을 직접 수정" onClick={() => setEditing(ids)}
              className="press shrink-0 rounded px-1.5 text-xs text-muted hover:bg-canvas hover:text-ink">수정</button>
          </div>
        )}
      </div>
    </li>
    )
  }

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label={`미리 보기 — ${title}`}
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="pop-in flex max-h-[85vh] w-full max-w-3xl flex-col rounded-2xl bg-white p-5 text-sm shadow-2xl">
        <h2 className="text-[15px] font-semibold text-ink">미리 보기 · {title}</h2>
        <p className="mt-1 break-keep text-xs text-muted">체크를 끈 행은 이 규칙에서 빠지고 다음에도 추천하지 않습니다. 적용은 변경 내역에 남아 되돌릴 수 있습니다.</p>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <label className="flex cursor-pointer items-center gap-1.5 text-[13px]">
            <input type="checkbox" checked={allOn} disabled={ready.length === 0} onChange={toggleAll}
              ref={(el) => { if (el) el.indeterminate = someOn && !allOn }} />
            전체 선택
          </label>
          {files.length > 1 && (
            <select aria-label="파일" value={file} onChange={(e) => setFile(e.target.value)} className="rounded-lg border border-line px-2 py-1 text-[13px]">
              <option value="">모든 파일</option>
              {files.map((f) => <option key={f} value={f}>{f}</option>)}
            </select>
          )}
        </div>
        <div className="mt-2 min-h-0 flex-1 overflow-y-auto">
          {preview.isLoading ? <p className="py-8 text-center text-muted">불러오는 중…</p>
            : preview.isError ? <p role="alert" className="py-8 text-center text-orange">미리 보기를 불러오지 못했습니다. {preview.error.message}</p>
            : (<>
              <ul>{group(ready).map(row)}</ul>
              {incomplete.length > 0 && (
                <section className="mt-4">
                  <div className="flex items-center gap-2">
                    <h3 className="text-[13px] font-semibold">기계 변환 안 됨 {incomplete.length}행</h3>
                    <Button onClick={() => rewrite.mutate()} disabled={rewrite.isPending}>{rewrite.isPending ? 'GPT로 고치는 중…' : 'GPT로 고치기'}</Button>
                  </div>
                  <ul>{group(incomplete).map(row)}</ul>
                </section>
              )}
              {(preview.data?.excluded.length ?? 0) > 0 && (
                <details className="mt-4 text-muted">
                  <summary className="cursor-pointer text-[13px]">뺀 행 {preview.data!.excluded.length}개</summary>
                  <ul>{preview.data!.excluded.map((r) => <li key={r.segmentId} className="py-1 text-xs">{label(r)} · {r.before}</li>)}</ul>
                </details>
              )}
            </>)}
        </div>
        <div className="mt-4 flex items-center gap-2">
          {result && <span className="text-[13px] text-primary">{result}</span>}
          <span className="ml-auto" />
          <Button onClick={onClose}>닫기</Button>
          <Button variant="primary" disabled={willApply.length === 0 || apply.isPending} onClick={() => apply.mutate()}>적용 ({willApply.length}행)</Button>
        </div>
      </div>
    </div>
  )
}
