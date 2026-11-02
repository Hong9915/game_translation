import { useEffect, useState } from 'react'
import type { ReviewerRef, WorkspaceSummary } from '../../api/types'

export type SaveState = 'saved' | 'saving' | 'error'
/**
 * propagated: 방금 저장으로 같이 바뀐(같은 원문) 다른 행 수. 0보다 크면 4초간 안내를 보여 준다.
 * propagatedSeq: 저장할 때마다 하나씩 늘어나는 값. 건수(propagated)가 지난번과 같아도(예: 2행→2행) 이 값은
 * 매번 바뀌므로, 알림이 이미 사라진 뒤 같은 건수가 다시 와도 놓치지 않고 다시 4초를 보여 준다.
 */
interface StatusBarProps {
  summary?: WorkspaceSummary; sheet: string | null; selection: string; saveState: SaveState; error?: string | null
  propagated?: number; propagatedSeq?: number; onDismissError?: () => void
  /** # 칸 색 범례: 지금 보이는 행을 마지막으로 고친 사람들. 없으면 숨긴다. */
  editors?: ReviewerRef[]
  /** 최종 리뷰 시트면 최종 수정 표시 수를 확정 뒤에 보인다. */
  finalReview?: boolean
}

const PROPAGATED_NOTICE_MS = 4000

/** 하단 상태바. 수치는 summary 기준(실시간 아님 — 저장·체크 뒤 재조회). 검토자별 막대는 담당 행 중 2차 체크 비율. */
export function StatusBar({ summary, sheet, selection, saveState, error, propagated, propagatedSeq, onDismissError, editors = [], finalReview = false }: StatusBarProps) {
  const n = (v: number) => v.toLocaleString()
  // 지금 보여줄 건수(없으면 null) — 이 값 하나가 "보이는지"와 "몇 행인지"를 함께 결정한다(propagated를
  // 별도 불리언과 함께 다시 확인하지 않는다 — 그러면 이번 저장이 0행이라 알림을 내려야 하는데 렌더 쪽
  // 조건에 propagated가 우연히 다시 끼어들어 버그를 가려버릴 수 있다).
  const [notice, setNotice] = useState<number | null>(null)
  useEffect(() => {
    if (!propagated) { setNotice(null); return }   // 이번 저장은 전파가 없었다 — 이전 저장 몫으로 떠 있던 알림을 바로 내린다.
    setNotice(propagated)
    const t = setTimeout(() => setNotice(null), PROPAGATED_NOTICE_MS)
    return () => clearTimeout(t)
    // propagated 값 자체가 지난번과 같을 수 있어(예: 2행→2행) propagatedSeq로 매 저장을 구분해 다시 트리거한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [propagatedSeq])
  return (
    <div className="flex flex-col border-t border-line bg-tableHead text-[11px] text-body">
      {error && (
        <div role="alert" className="flex items-center gap-2 border-b border-lineSoft bg-orangeSoft px-3 py-1 text-orange">
          <span className="flex-1">{error}</span>
          <button type="button" className="rounded px-2 text-muted hover:bg-white" onClick={onDismissError}>닫기</button>
        </div>
      )}
      <div className="flex items-center gap-4 px-3 py-1">
        {summary && <span>{n(summary.total)}행 · 수정됨 {n(summary.changed)} · A {n(summary.review1)} · B {n(summary.review2)} · 확인 필요 {n(summary.needsCheck)} · 확정 {n(summary.confirmed)}{finalReview ? ` · 최종 수정 ${n(summary.finalMark ?? 0)}` : ''}</span>}
        {summary?.reviewers.map((p) => (
          <span key={p.reviewer.id} className="inline-flex items-center gap-1">
            <span className="inline-block h-2 w-2 rounded-full" style={{ background: p.reviewer.color }} />
            <span>{p.reviewer.name}</span>
            <span className="inline-block h-1.5 w-20 overflow-hidden rounded bg-lineSoft align-middle"><span className="block h-full" style={{ width: `${p.assigned ? Math.round((p.done / p.assigned) * 100) : 0}%`, background: p.reviewer.color }} /></span>
            <span>{n(p.done)}/{n(p.assigned)}</span>
          </span>
        ))}
        {summary && <span>미배정 {n(summary.unassigned)}</span>}
        {notice !== null && <span className="text-purple">같은 원문 {n(notice)}행도 함께 수정됨</span>}
        {sheet && <span className="text-muted">({sheet})</span>}
        <span className="flex-1" />
        {editors.length > 0 && (
          <span role="group" aria-label="# 칸 색: 마지막으로 고친 사람" className="inline-flex items-center gap-2" title="# 칸 색: 이 행의 번역을 마지막으로 바꾼 사람(일괄 작업 포함)">
            <span className="text-muted">고친 사람</span>
            {editors.map((e) => (
              <span key={e.id} className="inline-flex items-center gap-1">
                <span className="inline-block h-2 w-2 rounded-full" style={{ background: e.color }} />{e.name}
              </span>
            ))}
          </span>
        )}
        {selection && <span>{selection}</span>}
        <span className="text-muted">Enter 편집 · Tab 다음 행 · ⌘Enter A</span>
        <span className={saveState === 'error' ? 'text-orange' : saveState === 'saving' ? 'text-muted' : 'text-green'}>
          {saveState === 'error' ? '● 저장 안 됨' : saveState === 'saving' ? '● 저장 중…' : '● '}
          {saveState === 'saved' && <span>저장됨</span>}
        </span>
      </div>
    </div>
  )
}
