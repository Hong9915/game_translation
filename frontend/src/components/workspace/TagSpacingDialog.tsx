import { useMutation, useQuery } from '@tanstack/react-query'
import { useDeferredValue, useEffect, useState } from 'react'
import { api } from '../../api/client'
import type { ReviewTarget } from '../../lib/reviewTarget'
import type { TagSpacingRow } from '../../api/types'
import { tagSpacingParts } from '../../lib/spacing'
import { Button } from '../ui/Button'

interface Props {
  /** 리뷰 범위(파일 화면의 시트 하나 또는 게임 화면의 고른 시트들). */
  target: ReviewTarget
  /** 제목에 붙는 범위 이름(시트 이름, 또는 "시트 3개"). */
  label: string
  /** 행을 고쳤을 때(그리드를 새로 읽게). 창은 열어 둔다. */
  onFixed: (applied: number) => void
  onClose: () => void
}

/** 고칠 꼴: 태그는 흐리게, 새로 넣는 공백은 칠해서 보인다. */
function After({ r }: { r: TagSpacingRow }) {
  return (
    <span className="whitespace-pre-wrap break-all">
      {tagSpacingParts(r.before, r.after).map((p, i) => p.kind === 'tag' ? <span key={i} className="text-muted">{p.text}</span>
        : p.kind === 'added' ? <mark key={i} title="새로 넣는 공백" className="rounded-sm bg-[#bbf7d0] px-[3px] text-inherit">{p.text}</mark>
        : <span key={i}>{p.text}</span>)}
    </span>
  )
}

/**
 * 리뷰 화면의 태그 띄어쓰기 창(2026-10-08). 지금 시트에서 태그가 든 행 가운데 Kiwi가 태그 경계에서 낱말이 갈린다고 본 행을 받아,
 * 고른 행만 고친다. 고친 행은 목록에서 접고 창은 열어 둔다.
 */
export function TagSpacingDialog({ target, label, onFixed, onClose }: Props) {
  const list = useQuery({ queryKey: ['tag-spacing', target], queryFn: () => api.review(target).tagSpacing(), staleTime: 0, refetchOnWindowFocus: false })
  const showSheet = target.kind === 'game'
  const [skipped, setSkipped] = useState<ReadonlySet<number>>(new Set())
  const [fixed, setFixed] = useState<ReadonlySet<number>>(new Set())
  const [search, setSearch] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const q = useDeferredValue(search).trim().toLowerCase()
  const left = (list.data ?? []).filter((r) => !fixed.has(r.segmentId))
  const shown = left.filter((r) => !q || [r.src, r.before].some((t) => t.toLowerCase().includes(q)))
  const chosen = shown.filter((r) => !skipped.has(r.segmentId))
  const allChosen = shown.length > 0 && chosen.length === shown.length

  const fix = useMutation({
    mutationFn: (items: TagSpacingRow[]) => api.review(target).fixTagSpacing(items.map(({ segmentId, before, after }) => ({ segmentId, before, after }))),
    onSuccess: (r, items) => {
      setFixed((s) => new Set([...s, ...items.map((x) => x.segmentId)]))
      const missed = items.length - r.applied
      setNotice(`${r.applied.toLocaleString()}행을 고쳤습니다.` + (missed > 0 ? ` ${missed.toLocaleString()}행은 그 사이 번역이 바뀌었거나 확정돼 건너뛰었습니다.` : ''))
      onFixed(r.applied)
    },
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

  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4" role="dialog" aria-modal="true" aria-label="태그 띄어쓰기"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }} onKeyDown={(e) => e.stopPropagation()}>
      <div className="pop-in flex max-h-[85vh] w-full max-w-3xl flex-col rounded-2xl bg-white p-5 text-sm shadow-2xl">
        <h2 className="text-[15px] font-semibold text-ink">태그 띄어쓰기 · {label}</h2>
        <p className="mt-1 break-keep text-xs text-muted">
          태그 바로 앞뒤에 낱말이 붙은 곳을 Kiwi 띄어쓰기로 보고, 띄워야 할 경계에만 공백을 넣습니다. 조사·숫자 뒤·줄바꿈 옆은 띄우지 않습니다.
          확정한 행은 보이지 않습니다. 고친 행은 변경 내역에 "태그 띄어쓰기" 한 건으로 남고 되돌릴 수 있습니다.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-lineSoft pb-2">
          <label className="flex cursor-pointer items-center gap-1.5 text-[13px] text-body">
            <input type="checkbox" aria-label="보이는 행 전체 선택" checked={allChosen} onChange={toggleAll} disabled={shown.length === 0} />전체
          </label>
          <span className="text-[13px] tabular-nums text-muted">{chosen.length.toLocaleString()} / {shown.length.toLocaleString()}행 고름</span>
          <input aria-label="원문·번역 검색" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="원문·번역 검색"
            className="ml-auto w-56 rounded-lg border border-line bg-white px-3 py-1.5 text-[13px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/10" />
        </div>
        {notice && <p role="status" className="mt-2 rounded-lg bg-[#ecfdf5] px-3 py-2 text-[13px] text-[#065f46]">{notice}</p>}
        {fix.isError && <p role="alert" className="mt-2 text-[13px] text-orange">{fix.error.message}</p>}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {list.isLoading ? <p className="py-12 text-center text-muted">태그가 든 행을 띄어쓰기 검사기로 보는 중…</p>
            : list.isError ? <p role="alert" className="py-12 text-center text-orange">태그 띄어쓰기를 불러오지 못했습니다. {list.error.message}</p>
            : shown.length === 0 ? <p className="py-12 text-center text-muted">고칠 행이 없습니다.</p>
            : (
              <ul>
                {shown.map((r) => (
                  <li key={r.segmentId} aria-label={r.after} className="border-b border-lineSoft py-2">
                    <label className="flex cursor-pointer items-start gap-3">
                      <input type="checkbox" className="mt-1" aria-label={r.after} checked={!skipped.has(r.segmentId)} onChange={() => toggle(r.segmentId)} />
                      <span className={`${showSheet ? 'w-28' : 'w-12'} shrink-0 truncate pt-px text-right text-xs tabular-nums text-muted`}>{showSheet && r.sheet ? `${r.sheet} ` : ''}{r.rowNo}행</span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-[14px] text-ink"><After r={r} /></span>
                        <span className="mt-0.5 block truncate text-xs text-muted">{r.src}</span>
                      </span>
                    </label>
                  </li>
                ))}
              </ul>
            )}
        </div>
        <div className="mt-4 flex items-center justify-end gap-2">
          <Button onClick={onClose}>닫기</Button>
          <Button variant="primary" disabled={chosen.length === 0 || fix.isPending} onClick={() => { setNotice(null); fix.mutate(chosen) }}>
            {chosen.length.toLocaleString()}행 고치기
          </Button>
        </div>
      </div>
    </div>
  )
}
