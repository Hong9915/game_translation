import { useMutation } from '@tanstack/react-query'
import { Fragment, useState, type ReactNode } from 'react'
import { api } from '../../api/client'
import type { GlossaryRequest, GlossaryVariant, GlossaryVariantExample } from '../../api/types'
import { Button } from '../ui/Button'
import { inputClass } from '../ui/Field'

/** literal = 원문의 DeepL 직역(참고용, 없으면 안 띄운다). */
interface Props { gameId: number; v: GlossaryVariant; literal?: string; done?: string; onDone: (find: string, message: string) => void; onUnify: (v: GlossaryVariant) => void }

const MARK = { src: 'bg-[#ede9fe]', main: 'bg-[#dcfce7]', alt: 'bg-[#ffedd5]' }

/** needles 순서대로 칠한다. 겹치면 앞의 것이 이긴다. */
function paint(text: string, needles: [string, string][]): ReactNode {
  let parts: [string, string | null][] = [[text, null]]
  for (const [needle, cls] of needles) {
    if (!needle) continue
    parts = parts.flatMap(([t, c]) => (c ? [[t, c]] : t.split(needle).flatMap((x, i) => (i ? [[needle, cls], [x, null]] : [[x, null]]))) as [string, string | null][])
  }
  return parts.map(([t, c], i) => <Fragment key={i}>{c ? <mark className={`rounded-sm px-px text-inherit ${c}`}>{t}</mark> : t}</Fragment>)
}

function Examples({ title, list, find, ko, cls }: { title: string; list: GlossaryVariantExample[]; find: string; ko: string | null; cls: string }) {
  if (list.length === 0) return null
  return (
    <div className="mt-2">
      <h3 className="text-[11.5px] font-semibold text-muted">{title}</h3>
      {list.map((e, i) => (
        <div key={i} className="grid grid-cols-1 gap-x-4 border-t border-dashed border-line py-1 sm:grid-cols-2">
          <div className="break-all text-body">{paint(e.src, [[find, MARK.src]])}</div>
          <div className="break-keep">{paint(e.ko, ko ? [[ko, cls]] : [])}</div>
        </div>
      ))}
    </div>
  )
}

/** 표기 갈림 한 구절. 처리하면(done) 한 줄로 접힌다. */
export function VariantCard({ gameId, v, literal, done, onDone, onUnify }: Props) {
  const [memo, setMemo] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: (body: GlossaryRequest) => api.glossary.upsert(gameId, body),
  })
  const named = v.alts.filter((a) => !a.spacing)
  const chip = 'rounded-full px-2 py-px text-[13px]'

  if (done) {
    return (
      <article aria-label={v.find} className="mb-2 rounded-xl border border-line bg-canvas px-4 py-2 text-[13px] text-muted">
        <span className="mr-2 text-base text-ink">{v.find}</span>처리함 · {done}
      </article>
    )
  }
  const saveMemo = () => save.mutate({ kind: 'SRC', matchMode: 'PARTIAL', find: v.find, ko: null, note: (memo ?? '').trim() },
    { onSuccess: () => onDone(v.find, '뜻이 다름, 메모로 남김') })
  const register = () => save.mutate({ kind: 'SRC', matchMode: 'PARTIAL', find: v.find, ko: v.main.ko },
    { onSuccess: () => onDone(v.find, `단어장에 등록, ${v.find} → ${v.main.ko}`) })

  return (
    <article aria-label={v.find} className="mb-2.5 rounded-xl border border-line bg-white px-4 py-3">
      <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
        <span className="text-[19px] font-semibold">{v.find}</span>
        {literal && <span className="rounded-full bg-[#e0f2fe] px-2 py-px text-[13px] text-[#075985]" title="DeepL 직역 — 참고용, 판정에는 쓰지 않는다">직역: {literal}</span>}
        <span className="text-xs tabular-nums text-muted">{v.rows.toLocaleString()}행</span>
        <span className={`${chip} bg-[#dcfce7] text-[#166534]`}>{v.main.ko} · {v.main.rows.toLocaleString()}행</span>
        <span className="text-muted" aria-hidden="true">↔</span>
        {v.alts.map((a) => <span key={a.ko} className={`${chip} bg-[#ffedd5] text-[#9a3412]`}>{a.ko} · {a.rows.toLocaleString()}행</span>)}
        {v.kind === 'MISSING' && <span className={`${chip} bg-[#ffedd5] text-[#9a3412]`}>빠짐 · {v.missingRows.toLocaleString()}행</span>}
        {v.standard && (
          <span className={`${chip} border border-[#0369a1] text-[#0369a1]`} title="Kiwi 띄어쓰기 교정이 고른 꼴 — 참고용. 전문 용어는 붙여 써도 맞춤법에 맞다">표준: {v.standard}</span>
        )}
        <span className="ml-auto flex gap-1.5">
          {v.kind !== 'MISSING' && <Button className="!px-2.5 !py-1 text-xs" onClick={() => onUnify(v)}>통일…</Button>}
          {v.kind === 'ALT' && memo === null && (
            <Button className="!px-2.5 !py-1 text-xs" onClick={() => setMemo(`문맥에 따라 ${[v.main.ko, ...named.map((a) => a.ko)].join('/')}`)}>뜻이 다름</Button>
          )}
          {v.kind === 'MISSING' && <Button className="!px-2.5 !py-1 text-xs" disabled={save.isPending} onClick={register}>단어장에 등록</Button>}
        </span>
      </div>
      {memo !== null && (
        <div className="mt-2 flex gap-2">
          <input aria-label="메모" className={`${inputClass} flex-1`} value={memo} onChange={(e) => setMemo(e.target.value)} />
          <Button variant="primary" className="!px-2.5 !py-1 text-xs" disabled={!memo.trim() || save.isPending} onClick={saveMemo}>메모 저장</Button>
          <Button className="!px-2.5 !py-1 text-xs" onClick={() => setMemo(null)}>취소</Button>
        </div>
      )}
      {save.isError && <p role="alert" className="mt-2 text-xs text-orange">{save.error.message}</p>}
      <Examples title="주 표기 예" list={v.main.examples} find={v.find} ko={v.main.ko} cls={MARK.main} />
      {v.alts.map((a) => <Examples key={a.ko} title={a.spacing ? `띄어쓰기가 다른 「${a.ko}」 예` : `다른 표기 「${a.ko}」 예`} list={a.examples} find={v.find} ko={a.ko} cls={MARK.alt} />)}
      <Examples title="주 표기가 없는 행" list={v.missing} find={v.find} ko={null} cls={MARK.alt} />
    </article>
  )
}
