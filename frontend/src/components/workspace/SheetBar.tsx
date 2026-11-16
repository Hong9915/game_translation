import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { api } from '../../api/client'
import { ROW_FILTER_LABEL, type RowFilter, type SearchIn, type SheetSummary } from '../../api/types'

interface SheetBarProps {
  sheets: SheetSummary[]; active: string | null; filter: RowFilter; q: string; hasReviewer: boolean
  onSheet: (s: string) => void; onFilter: (f: RowFilter) => void; onSearch: (q: string) => void
  // 검색어를 찾을 칸. 주면 검색창 앞에 고르기를 보인다(기본 = 원문·Fix, 기존 한국어 = 기존 한국어에서만).
  qIn?: SearchIn; onSearchIn?: (v: SearchIn) => void
  // 최종 리뷰 시트면 "최종 수정 표시/안 함" 필터를 보인다
  finalReview?: boolean
  // 주면 현재 시트의 "불일치 다시 계산" 버튼을 보인다
  projectId?: number
  // 불일치 다시 계산 버튼을 누를 수 있게 한다. 기본은 꺼짐 — RECOMPUTE_DISABLED_REASON 참고.
  recomputeEnabled?: boolean
  // 조사 확인 필터 옆에 보일 건수(요약의 josa)
  josaCount?: number
  // 조사 검사를 기다리는 행 수. 0보다 크면 필터 옆에 남은 행 수를 보인다.
  josaPending?: number
  // 필터 앞에 붙는 추가 컨트롤(보기 선택 등)
  children?: ReactNode
  // 주면 시트 탭 대신 그린다(게임 전체 리뷰 화면의 시트 고르기)
  tabs?: ReactNode
}

const FILTERS: RowFilter[] = ['ALL', 'CHANGED', 'NOT_REVIEW1', 'NOT_REVIEW2', 'NOT_REVIEW3', 'NOT_CONFIRMED', 'NEEDS_CHECK', 'FINAL_MARK', 'NOT_FINAL_MARK', 'MINE', 'UNASSIGNED', 'TERM_MISS', 'EMPTY_FIX', 'JOSA', 'DUP_CONFLICT']
const RECOMPUTE_NOTICE_MS = 4000
// 2026-09-27 운영에서 막아 둔 이유: 지금 있는 불일치 표시는 대부분 옛 용어 표 기준이라 단어장 기준으로 다시 계산하면
// 거의 다 지워지고, 4.9만 행 시트는 한 번에 10분쯤 걸린다(TermMissService.recompute가 바뀐 행마다 flush한다).
// 막혀 있는 동안은 버튼을 아예 그리지 않는다 — 늘 꺼져 있는 버튼은 자리만 차지한다(2026-09-30 UI 검토).

/** 시트 탭(행 수) + 필터 + 검색. 검색은 300ms 디바운스로 부모에 알린다. */
export function SheetBar({ sheets, active, filter, q, qIn = 'DEFAULT', onSearchIn, finalReview = false, hasReviewer, onSheet, onFilter, onSearch, projectId, recomputeEnabled = false, josaCount, josaPending, children, tabs }: SheetBarProps) {
  const [draft, setDraft] = useState(q)
  useEffect(() => { setDraft(q) }, [q])
  useEffect(() => {
    if (draft === q) return
    const h = setTimeout(() => onSearch(draft), 300)
    return () => clearTimeout(h)
  }, [draft, q, onSearch])
  return (
    <div className="flex items-end gap-3 border-b border-line bg-white px-3 pt-1">
      {tabs ?? <div role="tablist" className="flex min-w-0 shrink gap-0.5 overflow-x-auto">
        {sheets.map((s) => {
          const on = s.sheet === active
          return (
            <button key={s.sheet} type="button" role="tab" aria-selected={on} onClick={() => onSheet(s.sheet)}
              className={`press shrink-0 whitespace-nowrap rounded-t border border-b-0 px-2.5 py-1 text-xs ${on ? 'border-muted bg-white font-semibold text-ink' : 'border-line bg-canvas text-muted hover:bg-white hover:text-ink'}`}>
              {s.sheet} <span className="text-muted">({s.total.toLocaleString()})</span>
            </button>
          )
        })}
      </div>}
      {/* 창이 좁으면 도구들은 오른쪽에서 여러 줄로 내려간다. 줄이지 않으면 한글이 글자마다 꺾여 버튼이 세로로 깨진다. */}
      <div className="flex min-w-0 flex-1 flex-wrap items-end justify-end gap-x-3 [&>*]:shrink-0">
      {projectId !== undefined && active && recomputeEnabled && <RecomputeTermMiss key={active} projectId={projectId} sheet={active} />}
      {children}
      {josaPending !== undefined && josaPending > 0 && (
        // 줄은 items-end라 아래 끝을 맞춘다 — 옆 필터·쪽 버튼과 같은 투명 테두리·여백을 줘야 글자 높이가 같아진다.
        <span role="status" className="mb-1 whitespace-nowrap border border-transparent py-0.5 text-xs text-muted">조사 검사 중 · {josaPending.toLocaleString()}행 남음</span>
      )}
      <select aria-label="필터" value={filter} onChange={(e) => onFilter(e.target.value as RowFilter)} className="mb-1 rounded border border-line px-1.5 py-0.5 text-xs">
        {FILTERS.filter((f) => (f !== 'MINE' || hasReviewer) && ((f !== 'FINAL_MARK' && f !== 'NOT_FINAL_MARK') || finalReview)).map((f) => <option key={f} value={f}>{ROW_FILTER_LABEL[f]}{f === 'JOSA' && josaCount !== undefined ? ` (${josaCount.toLocaleString()})` : ''}</option>)}
      </select>
      {onSearchIn && (
        <select aria-label="검색할 칸" value={qIn} onChange={(e) => onSearchIn(e.target.value as SearchIn)} className="mb-1 rounded border border-line px-1.5 py-0.5 text-xs">
          <option value="DEFAULT">원문 · Fix</option>
          <option value="EXISTING_KO">기존 한국어</option>
          {/* 모아 보기(×N 칩) 중에만 나온다 — 고르는 칸이 아니라 지금 무엇으로 찾는지 보여 주는 칸 */}
          {qIn === 'SRC_EXACT' && <option value="SRC_EXACT">원문 그대로</option>}
          {qIn === 'SRC_CONTAINS' && <option value="SRC_CONTAINS">원문에 포함</option>}
        </select>
      )}
      <input type="search" aria-label="검색" placeholder={qIn === 'EXISTING_KO' ? '기존 한국어에서 검색' : '원문 · Fix 검색'} value={draft} onChange={(e) => setDraft(e.target.value)}
        className="mb-1 w-48 rounded border border-line px-2 py-0.5 text-xs" />
      </div>
    </div>
  )
}

/**
 * 단어장을 고친 뒤 현재 시트의 용어 불일치를 다시 계산하는 버튼(설계 §2.5). 단어장 저장은 일괄 적용이 건드린 행만
 * 다시 검사하므로, 나머지 행은 이 버튼으로 맞춘다. 성공하면 바뀐 행 수를 잠깐 보여 주고 목록·요약을 다시 읽는다.
 * StatusBar의 안내는 "같은 원문 함께 수정" 전용이라 여기서는 버튼 옆에 따로 띄운다.
 */
function RecomputeTermMiss({ projectId, sheet }: { projectId: number; sheet: string }) {
  const qc = useQueryClient()
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const recompute = useMutation({
    mutationFn: () => api.workspace.recomputeTermMiss(projectId, sheet),
    onMutate: () => { setNotice(null); setError(null) },
    onSuccess: ({ count }) => {
      setNotice(`불일치 ${count.toLocaleString()}행 갱신`)
      qc.invalidateQueries({ queryKey: ['workspace-rows', projectId] })
      qc.invalidateQueries({ queryKey: ['workspace-summary', projectId] })
    },
    onError: (e) => setError(e instanceof Error ? e.message : '다시 계산하지 못했습니다.'),
  })
  useEffect(() => {
    if (notice === null) return
    const t = setTimeout(() => setNotice(null), RECOMPUTE_NOTICE_MS)
    return () => clearTimeout(t)
  }, [notice])
  return (
    <div className="mb-1 flex items-center gap-2 text-xs">
      {notice && <span role="status" className="text-purple">{notice}</span>}
      {error && <span role="alert" className="text-orange">{error}</span>}
      <button type="button" disabled={recompute.isPending} onClick={() => recompute.mutate()}
        className="press rounded border border-line px-2 py-0.5 text-body hover:bg-canvas disabled:opacity-50">
        불일치 다시 계산
      </button>
    </div>
  )
}
