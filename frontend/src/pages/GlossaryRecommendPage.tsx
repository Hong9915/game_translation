import { useQuery } from '@tanstack/react-query'
import { Fragment, useDeferredValue, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { GlossaryRecommendation } from '../api/types'
import { VariantsTab } from '../components/glossary/VariantsTab'

type SortKey = 'rank' | 'find' | 'ko' | 'sentences' | 'rows' | 'share'
/** 서버가 준 한 줄에 화면 계산값을 붙인 것. rank = 문장 수 순위, ko = 첫 번째 지금 번역, share = 그 표기가 든 행의 비율. */
interface Line extends GlossaryRecommendation { rank: number; ko: string | null; share: number }

/** 표기가 이 비율보다 적은 행에만 들어 있으면 번역이 갈린다고 본다. */
const LOW_SHARE = 0.7
/** 많은 쪽이 먼저 보여야 하는 열. 나머지는 처음 누르면 오름차순. */
const DESC_FIRST: SortKey[] = ['sentences', 'rows']


function mark(text: string, needle: string | null): ReactNode {
  if (!needle) return text
  const parts = text.split(needle)
  return parts.map((part, i) => (
    <Fragment key={i}>{i > 0 && <mark className="rounded-sm bg-[#fff1b8] px-px text-inherit">{needle}</mark>}{part}</Fragment>
  ))
}

function compare(a: Line, b: Line, key: SortKey): number {
  const x = a[key], y = b[key]
  if (x === y) return a.rank - b.rank
  if (x === null) return 1    // 값이 없는 줄은 늘 맨 아래
  if (y === null) return -1
  return typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), 'ko')
}

/**
 * 단어장 추천 창. 단어장 패널의 "추천 보기"가 새 탭으로 연다. 게임 전체 원문에서 단어장에 없는 반복 구절을 전부 받아
 * 지금 번역·문장 수·표기 비율과 함께 표로 보여 준다. 추천 탭은 보기 전용이고, 표기 갈림 탭에서는 통일·메모를 바로 단어장에 남긴다.
 */
export function GlossaryRecommendPage() {
  const gameId = Number(useParams<{ gameId: string }>().gameId)
  const [tab, setTab] = useState<'recommend' | 'variants'>('recommend')
  const [variantsOpened, setVariantsOpened] = useState(false)   // 한 번 열면 탭을 바꿔도 처리한 카드가 남도록 계속 마운트해 둔다
  const [search, setSearch] = useState('')
  const [onlyLow, setOnlyLow] = useState(false)
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'rank', dir: 1 })
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  // 머리말 높이 — 표기 갈림 탭의 도구 막대가 스크롤해도 머리말 바로 아래에 붙도록 CSS 변수로 넘긴다(탭·필터에 따라 높이가 바뀐다)
  const headerRef = useRef<HTMLElement>(null)
  const [headerHeight, setHeaderHeight] = useState(0)
  useEffect(() => {
    const el = headerRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => setHeaderHeight(el.offsetHeight))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId) })
  const list = useQuery({ queryKey: ['glossary', gameId, 'RECOMMEND'], queryFn: () => api.glossary.recommend(gameId) })

  useEffect(() => { if (game.data) document.title = `단어장 추천 — ${game.data.name}` }, [game.data])

  const lines = useMemo<Line[]>(() => (list.data ?? []).map((r, i) => {
    const top = r.renderings[0] ?? null
    return {
      ...r, rank: i + 1, ko: top?.ko ?? null, share: top && r.rows > 0 ? top.rows / r.rows : 0,
    }
  }), [list.data])

  const q = useDeferredValue(search).trim().toLowerCase()
  const shown = useMemo(() => lines
    .filter((l) => (!q || [l.find, ...l.renderings.map((r) => r.ko)].some((t) => t.toLowerCase().includes(q)))
      && (!onlyLow || l.share < LOW_SHARE))
    .sort((a, b) => compare(a, b, sort.key) * sort.dir), [lines, q, onlyLow, sort])

  const sortBy = (key: SortKey) => setSort((s) => (s.key === key ? { key, dir: s.dir === 1 ? -1 : 1 } : { key, dir: DESC_FIRST.includes(key) ? -1 : 1 }))
  const toggle = (find: string) => setOpen((prev) => { const next = new Set(prev); if (!next.delete(find)) next.add(find); return next })

  const columns: { key: SortKey; label: string; className?: string }[] = [
    { key: 'rank', label: '#', className: 'w-14 text-right' },
    { key: 'find', label: '원문' },
    { key: 'ko', label: '지금 번역' },
    { key: 'sentences', label: '문장', className: 'w-20 text-right' },
    { key: 'rows', label: '행', className: 'w-20 text-right' },
    { key: 'share', label: '그 표기가 든 행', className: 'w-40' },
  ]
  const check = 'flex cursor-pointer items-center gap-1.5 whitespace-nowrap text-[13px] text-body'

  return (
    <div className="min-h-screen bg-white text-sm text-ink" style={{ '--recommend-header': `${headerHeight}px` } as CSSProperties}>
      <header ref={headerRef} className="sticky top-0 z-10 border-b border-line bg-white px-8 pb-3 pt-5">
        <h1 className="text-lg font-semibold leading-tight">단어장 추천 — {game.data?.name ?? '…'}</h1>
        <div role="tablist" aria-label="추천 창 보기" className="mt-2 flex gap-1">
          {([['recommend', '추천'], ['variants', '표기 갈림']] as const).map(([k, label]) => (
            <button key={k} type="button" role="tab" aria-selected={tab === k} onClick={() => { setTab(k); if (k === 'variants') setVariantsOpened(true) }}
              className={`press rounded-lg px-3 py-1 text-[13px] ${tab === k ? 'bg-primarySoft font-semibold text-primary' : 'text-body hover:bg-canvas'}`}>
              {label}
            </button>
          ))}
        </div>
        {tab === 'recommend' && (<>
        <p className="mt-1 break-keep text-xs text-muted">
          단어장에 없는 구절 가운데 서로 다른 문장 5개 이상에 나온 것 전부 · 숫자만 다른 행은 한 문장 · 행을 누르면 예문
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2">
          <input aria-label="원문·한국어 검색" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="원문·한국어 검색"
            className="w-64 rounded-lg border border-line bg-white px-3 py-1.5 text-[13px] outline-none transition-shadow focus:border-primary focus:ring-4 focus:ring-primary/10" />
          <label className={check}><input type="checkbox" checked={onlyLow} onChange={(e) => setOnlyLow(e.target.checked)} />표기 비율 70% 미만만</label>
          <span className="ml-auto text-[13px] tabular-nums text-muted">{shown.length.toLocaleString()} / {lines.length.toLocaleString()}개</span>
        </div>
        </>)}
      </header>

      <main className="px-8 pb-16">
        {variantsOpened && <div hidden={tab !== 'variants'}><VariantsTab gameId={gameId} /></div>}
        {tab === 'variants' ? null : list.isLoading ? <p className="py-16 text-center text-muted">게임 전체 원문에서 찾는 중…</p>
          : list.isError ? <p role="alert" className="py-16 text-center text-orange">추천을 불러오지 못했습니다. {list.error.message}</p>
          : lines.length === 0 ? <p className="break-keep py-16 text-center text-muted">추천할 구절이 없습니다. 단어장에 없는 구절이 서로 다른 문장 5개 이상에 나와야 합니다.</p>
          : (
            <table className="w-full border-collapse">
              <thead>
                <tr>
                  {columns.map((c) => (
                    <th key={c.key} scope="col" onClick={() => sortBy(c.key)}
                      aria-sort={sort.key === c.key ? (sort.dir === 1 ? 'ascending' : 'descending') : undefined}
                      className={`cursor-pointer select-none whitespace-nowrap border-b border-line px-2 pb-1.5 pt-3 text-left text-xs font-semibold text-muted hover:text-ink ${c.className ?? ''}`}>
                      {c.label}{sort.key === c.key && <span aria-hidden="true" className="ml-1">{sort.dir === 1 ? '▲' : '▼'}</span>}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {shown.map((l) => (
                  <Fragment key={l.find}>
                    <tr tabIndex={0} aria-expanded={open.has(l.find)} onClick={() => toggle(l.find)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(l.find) } }}
                      className="cursor-pointer border-b border-lineSoft outline-none hover:bg-canvas focus-visible:bg-primarySoft">
                      <td className="px-2 py-2 text-right tabular-nums text-muted">{l.rank}</td>
                      <td className="whitespace-nowrap px-2 py-2 text-base">{l.find}</td>
                      <td className="px-2 py-2 font-semibold">
                        {l.ko ?? <span className="rounded-full border border-line px-1.5 py-px text-[11px] font-normal text-muted">못 찾음</span>}
                      </td>
                      <td className="px-2 py-2 text-right tabular-nums">{l.sentences.toLocaleString()}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{l.rows.toLocaleString()}</td>
                      <td className="px-2 py-2 tabular-nums">
                        {l.ko !== null && (
                          <span className="flex items-center gap-2">
                            <span className="h-1.5 w-14 overflow-hidden rounded-full bg-lineSoft">
                              <span className={`block h-full ${l.share < LOW_SHARE ? 'bg-orange' : 'bg-green'}`} style={{ width: `${Math.round(l.share * 100)}%` }} />
                            </span>
                            <span>{Math.round(l.share * 100)}%</span>
                          </span>
                        )}
                      </td>
                    </tr>
                    {open.has(l.find) && (
                      <tr className="border-b border-lineSoft bg-canvas">
                        <td colSpan={columns.length} className="px-2 py-2 pl-[72px]">
                          {l.renderings.length === 0
                            ? <p className="break-keep py-1 text-xs text-muted">같은 행의 한국어에서 공통으로 나오는 표기를 찾지 못했습니다. 문장마다 다르게 옮겼거나 따로 옮기지 않는 말일 수 있습니다.</p>
                            : l.renderings.map((r) => (
                              <div key={r.ko} className="flex gap-4 border-b border-dashed border-line py-1.5 last:border-b-0">
                                <div className="w-40 shrink-0">
                                  <span className="font-semibold">{r.ko}</span>
                                  <span className="ml-2 text-xs tabular-nums text-muted">{r.rows.toLocaleString()}행</span>
                                </div>
                                <div className="min-w-0">
                                  <div className="break-all text-body">{mark(r.exampleSrc, l.find)}</div>
                                  <div className="break-keep">{mark(r.exampleKo, r.ko)}</div>
                                </div>
                              </div>
                            ))}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          )}
      </main>
    </div>
  )
}
