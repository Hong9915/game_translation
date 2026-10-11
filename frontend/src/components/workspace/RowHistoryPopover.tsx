import { useEffect, useRef, useState } from 'react'
import { ACTIVITY_KIND_LABEL, type RevisionAuthor, type RowHistoryEntry } from '../../api/types'
import { relativeTime } from '../../lib/relativeTime'
import { textDiff } from '../../lib/textDiff'
import { Anchored } from '../ui/Anchored'

/** 사람이 남기지 않은 판의 이름. */
const AUTHOR_LABEL: Record<RevisionAuthor, string> = {
  HUMAN: '직접 수정', LLM: 'GPT 번역', LLM_SHORTEN: 'GPT 줄이기', LLM_PROOFREAD: 'GPT 교정', GLOSSARY: '단어장 적용', EXISTING: '기존 번역', MEMORY: '번역 메모리',
}

export interface RowHistoryPopoverProps {
  x: number
  y: number
  rowNo: number
  load: () => Promise<RowHistoryEntry[]>
  /** 지금 Fix 본문. 이것과 같은 판에는 되돌리기 단추가 없다. */
  current: string | null
  /** 그 판의 글로 되돌린다 — 직접 고쳐 저장한 것과 같다. 없으면(작업 중인 행) 단추를 숨긴다. */
  onRestore?: (text: string) => void
  onClose: () => void
}

/**
 * 우클릭 "변경 내역 보기" 창(2026-10-08). 열릴 때 그 행의 판을 받아 최신 판부터 보이고, 판마다 바로 앞 판과 달라진 글자를 칠한다.
 * 지금 본문과 다른 판에는 "이 글로 되돌리기"가 있다(2026-10-08) — 누르면 그 글로 저장하고 닫힌다.
 * 바깥을 누르거나 Esc를 누르면 닫힌다.
 */
export function RowHistoryPopover({ x, y, rowNo, load, current, onRestore, onClose }: RowHistoryPopoverProps) {
  const ref = useRef<HTMLDivElement>(null)
  const [entries, setEntries] = useState<RowHistoryEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  // 열릴 때 한 번만 받는다
  useEffect(() => {
    let live = true
    load().then((r) => { if (live) setEntries(r) }, (e: unknown) => { if (live) setError(e instanceof Error ? e.message : '내역을 불러오지 못했습니다.') })
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const onDown = (e: globalThis.MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose() }
    const onEsc = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); onClose() } }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc, true)
    return () => { document.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onEsc, true) }
  }, [onClose])

  return (
    <Anchored ref={ref} role="dialog" aria-label={`${rowNo}행 변경 내역`} x={x} y={y} deps={[entries, error]}
      onKeyDown={(e) => e.stopPropagation()}
      className="glass pop-in z-50 flex max-h-[60vh] w-96 origin-top-left flex-col rounded-xl border border-line/70 p-2.5 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
      <div className="mb-1.5 text-xs font-medium text-muted">{rowNo}행 변경 내역</div>
      {error ? <div role="alert" className="px-1 py-2 text-orange">{error}</div>
        : entries === null ? <div className="px-1 py-3 text-center italic text-muted">불러오는 중…</div>
        : entries.length === 0 ? <div className="px-1 py-3 text-center text-muted">내역이 없습니다.</div>
        : (
          <ol className="min-h-0 flex-1 space-y-1.5 overflow-y-auto">
            {entries.map((e, i) => {
              const prev = entries[i + 1]?.text ?? null
              const kind = e.activityKind ? ACTIVITY_KIND_LABEL[e.activityKind] : null
              const restorable = onRestore && e.text.trim() !== '' && e.text.trim() !== (current ?? '').trim()
              return (
                <li key={e.version} className="group rounded-lg px-1.5 py-1 hover:bg-black/[0.03]">
                  <div className="flex items-center gap-1.5 text-xs text-muted">
                    {e.reviewer
                      ? <><span className="inline-block h-2 w-2 rounded-full" style={{ background: e.reviewer.color }} /><span className="font-medium text-ink">{e.reviewer.name}</span></>
                      : <span className="font-medium text-body">{AUTHOR_LABEL[e.author]}</span>}
                    {kind && <span>· {kind}</span>}
                    <span className="ml-auto whitespace-nowrap">{relativeTime(e.createdAt)}</span>
                  </div>
                  <div className="mt-0.5 whitespace-pre-wrap break-all text-ink">
                    {textDiff(prev, e.text).map((p, k) => p.kind === 'added' ? <ins key={k} className="rounded-sm bg-[#bbf7d0] no-underline">{p.text}</ins>
                      : p.kind === 'removed' ? <del key={k} className="rounded-sm bg-[#fee2e2] text-[#b91c1c]">{p.text}</del>
                      : <span key={k}>{p.text}</span>)}
                  </div>
                  {restorable && (
                    <div className="mt-0.5 flex justify-end">
                      <button type="button" onClick={() => { onRestore(e.text); onClose() }}
                        className="rounded-md px-1.5 py-0.5 text-xs text-primary opacity-60 hover:bg-primarySoft hover:opacity-100 focus-visible:opacity-100 group-hover:opacity-100">
                        이 글로 되돌리기
                      </button>
                    </div>
                  )}
                </li>
              )
            })}
          </ol>
        )}
    </Anchored>
  )
}
