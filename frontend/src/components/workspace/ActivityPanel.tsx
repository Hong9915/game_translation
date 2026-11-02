import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, api } from '../../api/client'
import { ACTIVITY_KIND_LABEL, GLOSSARY_ACTION_LABEL, type ActivityFeedItem, type ActivityRowItem } from '../../api/types'
import { relativeTime } from '../../lib/relativeTime'

/** 게임 전체 리뷰 화면(설계 2026-10-12): 범위에 든 파일들 + 게임 단위 항목을 보이고, 어느 파일 행이든 점프한다. */
export interface ActivityGameScope { gameId: number; sheetIds: number[]; fileNames: Record<number, string> }

export interface ActivityPanelProps {
  /** 파일 화면의 프로젝트. 게임 화면이면 game을 대신 준다. */
  projectId?: number
  game?: ActivityGameScope
  onClose: () => void
  /** 항목·펼친 행을 누르면 그 시트의 순번(전체 보기 0-기반)으로 간다. 게임 화면이면 그 행의 파일도 준다. */
  onJump: (sheet: string, ordinal: number, projectId?: number) => void
}

/** 패널 안에서 같이 쓰는 화면 정보. key는 이 화면의 캐시 키(['activity', key], ['workspace-rows', key]). */
interface Ctx { projectId?: number; game?: ActivityGameScope; key: number | string; onJump: ActivityPanelProps['onJump'] }

const ROWS_PAGE = 20
const JOB_KINDS = new Set(['FIX_TERMS', 'SHORTEN', 'GLOSSARY_APPLY', 'REVERT', 'JOSA_FIX', 'TAG_SPACING', 'COMMAND_FIX'])
/** 되돌릴 수 있는 종류. 단어장 편집은 항목 자체를 단어장에서 고친다. */
const REVERTIBLE = new Set(['EDIT', 'RETRANSLATE', 'FIX_TERMS', 'SHORTEN', 'GLOSSARY_APPLY', 'REVERT', 'JOSA_FIX', 'TAG_SPACING', 'COMMAND_FIX'])
const GLOSSARY_APPLY_NOTE = '단어장 항목은 그대로 남아 다음 일괄 적용 때 다시 바뀝니다. 항목을 고치거나 지우려면 단어장에서 하세요.'

/** 검수 워크스페이스 오른쪽 변경 내역 패널(설계 2026-09-30 §4). 단어장 패널과 같은 자리·같은 폭. */
export default function ActivityPanel({ projectId, game, onClose, onJump }: ActivityPanelProps) {
  const key = game ? `g${game.gameId}` : projectId ?? 0
  const ctx: Ctx = { projectId, game, key, onJump }
  const feed = useInfiniteQuery({
    queryKey: game ? ['activity', key, game.sheetIds] : ['activity', key],
    queryFn: ({ pageParam }) => (game ? api.activity.gameFeed(game.gameId, game.sheetIds, pageParam) : api.activity.feed(projectId ?? 0, pageParam)),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => last.nextBefore,
  })
  const items = feed.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <aside className="flex h-full w-[380px] flex-none flex-col border-l border-line bg-white text-sm text-ink">
      <div className="flex items-center gap-2 px-4 pb-2 pt-3">
        <h2 className="text-[15px] font-semibold">변경 내역</h2>
        <div className="flex-1" />
        <button type="button" aria-label="닫기" onClick={onClose} className="press flex h-7 w-7 items-center justify-center rounded-full text-base text-muted hover:bg-canvas">×</button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {feed.isError && <p role="alert" className="px-4 py-3 text-[13px] text-orange">변경 내역을 불러오지 못했습니다.</p>}
        {feed.isSuccess && items.length === 0 && <p className="px-4 py-3 text-[13px] text-muted">아직 변경 내역이 없습니다.</p>}
        <ul>
          {items.map((it) => <Item key={it.id} item={it} ctx={ctx} />)}
        </ul>
        {feed.hasNextPage && (
          <div className="px-4 py-3">
            <button type="button" onClick={() => feed.fetchNextPage()} disabled={feed.isFetchingNextPage}
              className="press w-full rounded-lg border border-line py-1.5 text-xs text-muted hover:bg-canvas">더 보기</button>
          </div>
        )}
      </div>
    </aside>
  )
}

function Item({ item, ctx }: { item: ActivityFeedItem; ctx: Ctx }) {
  const [open, setOpen] = useState(false)
  const isEdit = item.kind === 'EDIT' || item.kind === 'RETRANSLATE'
  // EDIT의 "함께 바뀐 행"은 대표 행을 뺀 수. 대표 행이 없으면(같은 값 저장) 전부가 따라 바뀐 행이다.
  const others = isEdit ? item.rowCount - (item.primary ? 1 : 0) : item.rowCount
  return (
    <li className="border-b border-line px-4 py-2.5">
      <div className="flex items-center gap-1.5 text-[11px] text-muted">
        {item.reviewer
          ? <><span className="inline-block h-2 w-2 rounded-full" style={{ background: item.reviewer.color }} /><span className="text-ink">{item.reviewer.name}</span></>
          : <span>이름 없음</span>}
        <span>·</span>
        <span>{relativeTime(item.createdAt)}</span>
        <span>·</span>
        <span>{ACTIVITY_KIND_LABEL[item.kind]}</span>
      </div>
      {item.kind === 'GLOSSARY_EDIT' && <GlossaryLine item={item} />}
      {isEdit && (
        <div className="mt-1">
          {item.primary && (
            <>
              <RowLink row={item.primary} ctx={ctx} />
              <BeforeAfter before={item.primary.before} after={item.primary.after} />
            </>
          )}
          {others > 0 && (
            <button type="button" onClick={() => setOpen((v) => !v)}
              className="press mt-1 rounded-full border border-purple/30 bg-purpleSoft px-2 py-0.5 text-[11px] text-purple">
              {`같은 원문 ${others}행${item.primary ? ' 함께' : ''}`}
            </button>
          )}
        </div>
      )}
      {JOB_KINDS.has(item.kind) && (
        item.rowCount === 0
          ? <p className="mt-1 text-[13px] text-muted">바뀐 행 없음</p>
          : <button type="button" onClick={() => setOpen((v) => !v)} className="press mt-1 text-[13px] text-ink hover:underline">
              {`${ctx.game && item.projectId ? `${ctx.game.fileNames[item.projectId] ?? ''} · ` : ''}${item.sheet ? `${item.sheet} · ` : ''}${item.rowCount.toLocaleString()}행`}
            </button>
      )}
      {open && <Rows activityId={item.id} ctx={ctx} />}
      {REVERTIBLE.has(item.kind) && item.rowCount > 0 && <Revert item={item} cacheKey={ctx.key} />}
    </li>
  )
}

/**
 * 되돌리기(2026-09-30 사용자 결정): 그 항목이 건드린 행만 이전 본문으로, 전파 없이. 그 사이 바뀐 행·확정 행·첫 판 행은 서버가 건너뛴다.
 * 버튼 → 같은 자리 확인 → 결과 문구. 피드 갱신은 서버의 refresh 이벤트가 하지만, 이벤트가 늦어도 바로 보이게 여기서도 무효화한다.
 */
function Revert({ item, cacheKey }: { item: ActivityFeedItem; cacheKey: number | string }) {
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const revert = useMutation({
    mutationFn: () => api.activity.revert(item.id),
    onSuccess: (r) => {
      setResult(`${r.reverted}행 되돌림${r.skipped > 0 ? ` · ${r.skipped}행 건너뜀` : ''}`)
      qc.invalidateQueries({ queryKey: ['activity', cacheKey] })
      qc.invalidateQueries({ queryKey: ['workspace-rows', cacheKey] })
    },
    onError: (e) => setResult(e instanceof ApiError ? e.message : '되돌리지 못했습니다.'),
    onSettled: () => setConfirming(false),
  })
  if (result) return <p role="status" className="mt-1 text-[11px] text-muted">{result}</p>
  if (confirming) {
    return (
      <div className="mt-1.5 rounded-lg bg-canvas px-2.5 py-2 text-[12px]">
        <p className="break-keep">{`${item.rowCount.toLocaleString()}행을 이전 본문으로 되돌립니다.`}{item.kind === 'GLOSSARY_APPLY' && <span className="text-muted"> {GLOSSARY_APPLY_NOTE}</span>}</p>
        <div className="mt-1.5 flex gap-1.5">
          <button type="button" onClick={() => revert.mutate()} disabled={revert.isPending}
            className="press rounded-md bg-purple px-2 py-0.5 text-[11px] text-white disabled:opacity-60">예, 되돌리기</button>
          <button type="button" onClick={() => setConfirming(false)} disabled={revert.isPending}
            className="press rounded-md border border-line bg-white px-2 py-0.5 text-[11px]">취소</button>
        </div>
      </div>
    )
  }
  return (
    <button type="button" onClick={() => setConfirming(true)}
      className="press mt-1.5 inline-flex items-center gap-1 rounded-md border border-purple/40 bg-white px-2 py-0.5 text-[11px] font-medium text-purple hover:bg-purpleSoft">
      <span aria-hidden="true">↶</span>되돌리기
    </button>
  )
}

function GlossaryLine({ item }: { item: ActivityFeedItem }) {
  const d = item.detail
  const action = d.action ? GLOSSARY_ACTION_LABEL[d.action] : ''
  return (
    <p className="mt-1 flex items-center gap-1.5 text-[13px]">
      <span>{`단어장 「${d.find ?? ''} → ${d.ko ?? ''}」 ${action}`}</span>
      {d.kind && <span className="rounded bg-canvas px-1 text-[11px] tracking-[0.01em] text-muted">{d.kind}</span>}
    </p>
  )
}

/**
 * 같은 원문 전파는 게임의 다른 파일 행도 바꾼다 — 파일 화면에서는 그 행이 없으므로 점프 없이 표시만 한다.
 * 게임 화면은 게임의 어느 파일 행이든 간다(파일 이름을 앞에 붙인다).
 */
function RowLink({ row, ctx }: { row: ActivityRowItem; ctx: Ctx }) {
  if (ctx.game) {
    return (
      <button type="button" onClick={() => ctx.onJump(row.sheet, row.ordinal, row.projectId)} className="press text-left text-[13px] font-medium text-primary hover:underline">
        {`${ctx.game.fileNames[row.projectId] ?? '다른 파일'} · ${row.sheet} ${row.rowNo}행`}
      </button>
    )
  }
  if (row.projectId !== ctx.projectId) return <span className="text-[13px] text-muted">{`다른 파일 · ${row.sheet} ${row.rowNo}행`}</span>
  return (
    <button type="button" onClick={() => ctx.onJump(row.sheet, row.ordinal)} className="press text-[13px] font-medium text-primary hover:underline">
      {`${row.sheet} ${row.rowNo}행`}
    </button>
  )
}

/** 이전 본문은 취소선·회색, 이후 본문은 그대로. 첫 판(before null)은 이전 줄을 숨긴다. 긴 글은 2줄로 자르고 title로 전체를 본다. */
function BeforeAfter({ before, after }: { before: string | null; after: string }) {
  return (
    <div className="mt-0.5 text-[13px] leading-snug">
      {before !== null && <p className="line-clamp-2 break-all text-muted line-through" title={before}>{before}</p>}
      <p className="line-clamp-2 break-all" title={after}>{after}</p>
    </div>
  )
}

/** 펼친 행 목록. 20행씩 이어 읽는다(설계 §3.2). */
function Rows({ activityId, ctx }: { activityId: number; ctx: Ctx }) {
  const pages = useInfiniteQuery({
    queryKey: ['activity-rows', activityId],
    queryFn: ({ pageParam }) => api.activity.rows(activityId, pageParam, ROWS_PAGE),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
  })
  const rows = pages.data?.pages.flatMap((p) => p.rows) ?? []
  const total = pages.data?.pages[0]?.total ?? 0
  const left = total - rows.length
  return (
    <div className="mt-1.5 border-l-2 border-line pl-2">
      {pages.isError && <p role="alert" className="text-[12px] text-orange">행 목록을 불러오지 못했습니다.</p>}
      <ul className="space-y-1.5">
        {rows.map((r) => (
          <li key={r.segmentId}>
            <RowLink row={r} ctx={ctx} />
            <BeforeAfter before={r.before} after={r.after} />
          </li>
        ))}
      </ul>
      {pages.hasNextPage && (
        <button type="button" onClick={() => pages.fetchNextPage()} disabled={pages.isFetchingNextPage} className="press mt-1 text-[12px] text-muted hover:underline">
          {`더 보기 (${left.toLocaleString()}행 남음)`}
        </button>
      )}
    </div>
  )
}
