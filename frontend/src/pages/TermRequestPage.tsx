import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { TermApproveItem, TermCategory, TermRequestDetail, TermRequestItem } from '../api/types'
import { Button } from '../components/ui/Button'
import { Chip } from '../components/ui/Chip'

const CATEGORY_LABEL: Record<TermCategory, string> = {
  CHARACTER: '캐릭터', ITEM: '아이템', SKILL: '스킬', PLACE: '장소', MONSTER: '몬스터', CURRENCY: '화폐', OTHER: '기타',
}
const CATEGORIES = Object.keys(CATEGORY_LABEL) as TermCategory[]

type Tab = 'all' | 'split' | 'corpus' | 'new' | 'approved'
const TABS: { key: Tab; label: string; test: (i: TermRequestItem) => boolean }[] = [
  { key: 'all', label: '전체', test: () => true },
  { key: 'split', label: '표기 갈림', test: (i) => i.status !== 'APPROVED' && i.renderings.length >= 2 },
  { key: 'corpus', label: '번역본에 있음', test: (i) => i.status !== 'APPROVED' && i.source === 'CORPUS' },
  { key: 'new', label: '처음 나옴', test: (i) => i.status !== 'APPROVED' && i.source === 'GPT' },
  { key: 'approved', label: '승인됨', test: (i) => i.status === 'APPROVED' },
]

/** 줄마다 사람이 고친 값. keep = 체크를 풀어 남기기로 한 다른 표기(나머지 다른 표기는 바꾸기). */
interface Draft { ko: string; keep: ReadonlySet<string> }

/** 처음 보이는 최종 표기: 승인한 값 → 번역본 속 주 표기 → 단어장에 이미 있는 표기 → GPT 초안. */
const initialKo = (i: TermRequestItem) => i.finalKo ?? i.renderings[0]?.ko ?? i.existingKo ?? i.gptKo ?? ''
/** 원문 1글자는 번역본 대조를 하지 않고 칸 전체가 같을 때만 적용한다. */
const singleChar = (i: TermRequestItem) => [...i.src].length === 1
/** GPT로 표기 확인 대상: 승인 안 됐고 2글자 이상이며 아직 확인 안 한 항목(서버 TermRequestService.needsFind와 같다). */
const needsFind = (i: TermRequestItem) => i.status !== 'APPROVED' && !i.gptChecked && !singleChar(i)

function mark(text: string, needle: string): ReactNode {
  if (!needle) return text
  return text.split(needle).map((part, i) => (
    <Fragment key={i}>{i > 0 && <mark className="rounded-sm bg-[#fff1b8] px-px text-inherit">{needle}</mark>}{part}</Fragment>
  ))
}

/**
 * 단어장 요청 처리 화면(시안 A). 개발자가 채운 양식의 용어를 한 표에 놓고, 번역본 속 표기나 GPT 초안 가운데 최종 표기를 골라 승인한다.
 * 승인하면 단어장에 들어가고, 체크한 다른 표기(기본 바꾸기)는 기존 번역에서 최종 표기로 한 번에 바뀐다.
 */
export function TermRequestPage() {
  const params = useParams<{ gameId: string; requestId: string }>()
  const gameId = Number(params.gameId)
  const requestId = Number(params.requestId)
  const qc = useQueryClient()
  const key = ['termRequest', requestId]

  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId) })
  const request = useQuery({ queryKey: key, queryFn: () => api.termRequests.get(requestId) })

  const [tab, setTab] = useState<Tab>('all')
  const [category, setCategory] = useState<TermCategory | ''>('')
  const [drafts, setDrafts] = useState<ReadonlyMap<number, Draft>>(new Map())
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set())
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set())
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { if (game.data) document.title = `단어장 요청 — ${game.data.name}` }, [game.data])
  useEffect(() => {
    if (!notice) return
    const t = setTimeout(() => setNotice(null), 4000)
    return () => clearTimeout(t)
  }, [notice])

  const generate = useMutation({
    mutationFn: () => api.termRequests.generate(requestId),
    onSuccess: (d) => { qc.setQueryData<TermRequestDetail>(key, d); setError(null) },
    onError: (e) => setError(`GPT 초안을 만들지 못했습니다. ${e.message}`),
  })
  // 페이지를 열 때 PENDING이 있으면 요청 id마다 한 번만 GPT 초안을 부른다
  const generatedFor = useRef<number | null>(null)
  const hasPending = request.data?.items.some((i) => i.status === 'PENDING') ?? false
  useEffect(() => {
    if (!hasPending || generatedFor.current === requestId) return
    generatedFor.current = requestId
    generate.mutate()
  }, [hasPending, requestId, generate])

  // GPT로 표기 확인(2026-11-02): 버튼을 눌렀을 때만 — 용어마다 GPT가 행을 열어 보므로 시간·비용이 든다
  const find = useMutation({
    mutationFn: () => api.termRequests.find(requestId),
    onSuccess: (d) => {
      const before = new Set((qc.getQueryData<TermRequestDetail>(key)?.items ?? []).filter((i) => i.gptChecked).map((i) => i.id))
      const fresh = new Set(d.items.filter((i) => i.gptChecked && !before.has(i.id)).map((i) => i.id))
      qc.setQueryData<TermRequestDetail>(key, d)
      // 표기가 바뀐 줄은 전에 고른 최종 표기·남길 표기를 버린다 — 남겨 두면 새 주 표기가 바꿀 표기가 되어 승인 때 거꾸로 고친다
      setDrafts((prev) => new Map([...prev].filter(([id]) => !fresh.has(id))))
      setSelected((prev) => new Set([...prev].filter((id) => !fresh.has(id))))
      setError(null)
      setNotice(`${fresh.size}개 확인`)
    },
    onError: (e) => setError(`GPT로 표기를 확인하지 못했습니다. ${e.message}`),
  })

  const approve = useMutation({
    mutationFn: (items: TermApproveItem[]) => api.termRequests.approve(requestId, items),
    onSuccess: (r, items) => {
      qc.setQueryData<TermRequestDetail>(key, r.request)
      const done = new Set(items.map((i) => i.itemId))
      setDrafts((prev) => new Map([...prev].filter(([id]) => !done.has(id))))
      setSelected((prev) => new Set([...prev].filter((id) => !done.has(id))))
      setError(null)
      setNotice(`${r.approved}개 승인 · 기존 번역 ${r.applied.toLocaleString()}행 고침`)
    },
    onError: (e) => {
      setError(`승인하지 못했습니다. ${e.message}`)
      // 앞 항목은 승인됐을 수 있다(항목마다 따로 저장) — 다시 읽어 승인됨으로 보여 준다
      qc.invalidateQueries({ queryKey: key })
    },
  })

  const items = useMemo(() => request.data?.items ?? [], [request.data])
  const draftOf = (i: TermRequestItem): Draft => drafts.get(i.id) ?? { ko: initialKo(i), keep: new Set() }
  /** 최종 표기(앞뒤 공백 뺀 값)와 다른 번역본 속 표기. 1글자 원문은 대조하지 않는다. */
  const othersOf = (i: TermRequestItem, ko: string) => (singleChar(i) ? [] : i.renderings.filter((r) => r.ko !== ko.trim()))
  const replaceOf = (i: TermRequestItem) => {
    const d = draftOf(i)
    return othersOf(i, d.ko).filter((r) => !d.keep.has(r.ko))
  }
  const payloadOf = (i: TermRequestItem): TermApproveItem => ({ itemId: i.id, ko: draftOf(i).ko.trim(), replace: replaceOf(i).map((r) => r.ko) })

  // 최종 표기가 바뀌면 전에 최종이던 표기가 다시 다른 표기가 되므로 기본(바꾸기)으로 돌린다
  const setKo = (i: TermRequestItem, ko: string) => setDrafts((prev) => {
    const d = prev.get(i.id) ?? { ko: initialKo(i), keep: new Set<string>() }
    const keep = new Set(d.keep)
    keep.delete(d.ko.trim())
    return new Map(prev).set(i.id, { ko, keep })
  })
  const toggleKeep = (i: TermRequestItem, ko: string) => setDrafts((prev) => {
    const d = prev.get(i.id) ?? { ko: initialKo(i), keep: new Set<string>() }
    const keep = new Set(d.keep)
    if (!keep.delete(ko)) keep.add(ko)
    return new Map(prev).set(i.id, { ...d, keep })
  })
  const toggleIn = (set: (f: (prev: ReadonlySet<number>) => ReadonlySet<number>) => void, id: number) =>
    set((prev) => { const next = new Set(prev); if (!next.delete(id)) next.add(id); return next })

  const inCategory = items.filter((i) => !category || i.category === category)
  const shown = inCategory.filter(TABS.find((t) => t.key === tab)!.test)
  // 승인된 줄은 이 화면에서 읽기 전용이다(바꾸려면 단어장에서) — 일괄 승인·바뀔 행 수에서 뺀다
  const chosen = items.filter((i) => selected.has(i.id) && draftOf(i).ko.trim() !== '' && i.status !== 'PENDING' && i.status !== 'APPROVED')
  const changeRows = chosen.reduce((sum, i) => sum + replaceOf(i).reduce((s, r) => s + r.rows, 0), 0)

  const th = 'whitespace-nowrap border-b border-line px-2 pb-1.5 pt-3 text-left text-xs font-semibold text-muted'
  const COLS = 7

  return (
    <div className="min-h-screen bg-white text-sm text-ink">
      <header className="sticky top-0 z-10 border-b border-line bg-white px-8 pb-3 pt-5">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <Link to={`/games/${gameId}`} aria-label="게임으로" title="게임으로"
              className="press inline-flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-lg border border-line bg-white text-base text-body hover:bg-canvas hover:text-ink">←</Link>
            <div>
              <h1 className="text-lg font-semibold leading-tight">단어장 요청 — {game.data?.name ?? '…'}</h1>
              {request.data && <p className="mt-0.5 text-xs text-muted">{request.data.fileName} · {new Date(request.data.createdAt).toLocaleDateString('ko-KR')}</p>}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-3">
            {chosen.length > 0 && <span className="text-[13px] tabular-nums text-body">기존 번역 약 {changeRows.toLocaleString()}행이 바뀝니다</span>}
            <Button variant="primary" disabled={chosen.length === 0 || approve.isPending} onClick={() => approve.mutate(chosen.map(payloadOf))}>
              선택한 {chosen.length}개 승인
            </Button>
          </div>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2">
          <div role="tablist" aria-label="요청 항목 보기" className="flex gap-1">
            {TABS.map((t) => (
              <button key={t.key} type="button" role="tab" aria-selected={tab === t.key} onClick={() => { setTab(t.key); setSelected(new Set()) }}
                className={`press rounded-lg px-3 py-1 text-[13px] ${tab === t.key ? 'bg-primarySoft font-semibold text-primary' : 'text-body hover:bg-canvas'}`}>
                {t.label} {inCategory.filter(t.test).length}
              </button>
            ))}
          </div>
          <select aria-label="분류" value={category} onChange={(e) => { setCategory(e.target.value as TermCategory | ''); setSelected(new Set()) }}
            className="rounded-lg border border-line bg-white px-2 py-1 text-[13px] outline-none focus:border-primary">
            <option value="">전체 분류</option>
            {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
          </select>
          {/* 초안 만들기와 같은 항목을 함께 고치면 나중에 저장한 쪽이 덮으므로 겹쳐 돌리지 않는다 */}
          <Button disabled={find.isPending || generate.isPending || !items.some(needsFind)} onClick={() => find.mutate()}>
            {find.isPending ? '확인 중…' : 'GPT로 표기 확인'}
          </Button>
        </div>
        {notice && <p role="status" className="mt-2 text-[13px] text-green">{notice}</p>}
        {error && <p role="alert" className="mt-2 text-[13px] text-orange">{error}</p>}
      </header>

      <main className="px-8 pb-16">
        {request.isLoading ? <p className="py-16 text-center text-muted">불러오는 중…</p>
          : request.isError ? <p role="alert" className="py-16 text-center text-orange">요청을 불러오지 못했습니다. {request.error.message}</p>
          : (
            <table className="w-full border-collapse">
              <thead>
                <tr>
                  <th scope="col" className={`${th} w-8`}><span className="sr-only">선택</span></th>
                  <th scope="col" className={`${th} w-20`}>분류</th>
                  <th scope="col" className={th}>원문</th>
                  <th scope="col" className={th}>번역본 속 표기</th>
                  <th scope="col" className={th}>최종 표기</th>
                  <th scope="col" className={th}>다른 표기</th>
                  <th scope="col" className={`${th} w-28`}>승인</th>
                </tr>
              </thead>
              <tbody>
                {shown.length === 0 && (
                  <tr><td colSpan={COLS} className="py-12 text-center text-muted">이 보기에 해당하는 용어가 없습니다.</td></tr>
                )}
                {shown.map((i) => {
                  const d = draftOf(i)
                  const others = othersOf(i, d.ko)
                  const ko = d.ko.trim()
                  const gpt = i.gptKo !== null && ko === i.gptKo
                  const approved = i.status === 'APPROVED'
                  // 자동 초안 요청이 실패하면 PENDING 줄은 만드는 중이 아니라 실패로 보여 다시 만들게 한다
                  const failed = i.status === 'FAILED' || (i.status === 'PENDING' && generate.isError)
                  const existing = i.existingKo ?? i.existingNote
                  return (
                    <Fragment key={i.id}>
                      <tr className="border-b border-lineSoft align-top hover:bg-canvas">
                        <td className="px-2 py-2">
                          {!approved && <input type="checkbox" aria-label={`${i.src} 선택`} checked={selected.has(i.id)} onChange={() => toggleIn(setSelected, i.id)} />}
                        </td>
                        <td className="whitespace-nowrap px-2 py-2 text-body">{CATEGORY_LABEL[i.category]}</td>
                        <td className="px-2 py-2">
                          <button type="button" aria-expanded={open.has(i.id)} onClick={() => toggleIn(setOpen, i.id)}
                            className="whitespace-nowrap text-left text-base hover:underline">{i.src}</button>
                          {existing && <div className="text-xs text-muted">이미 있음: {existing}</div>}
                        </td>
                        <td className="px-2 py-2">
                          {i.renderings.length === 0
                            ? <span className="text-xs text-muted">번역본에 없음</span>
                            : <div className="flex flex-wrap gap-1">
                                {i.renderings.map((r) => approved
                                  ? <span key={r.ko} className={`rounded-full border px-2 py-0.5 text-[13px] ${ko === r.ko ? 'border-primary bg-primarySoft text-primary' : 'border-line'}`}>
                                      {r.ko} <span className="tabular-nums text-muted">{r.rows.toLocaleString()}</span>
                                    </span>
                                  : <button key={r.ko} type="button" onClick={() => setKo(i, r.ko)}
                                      className={`press rounded-full border px-2 py-0.5 text-[13px] ${ko === r.ko ? 'border-primary bg-primarySoft text-primary' : 'border-line bg-white hover:bg-canvas'}`}>
                                      {r.ko} <span className="tabular-nums text-muted">{r.rows.toLocaleString()}</span>
                                    </button>)}
                              </div>}
                          {i.gptChecked && <span className="mt-1 block text-[11px] text-muted">GPT 확인</span>}
                          {i.gptFindReason && <p className="mt-0.5 text-xs text-muted">{i.gptFindReason}</p>}
                        </td>
                        <td className="px-2 py-2">
                          {approved
                            ? <span className="font-semibold">{i.finalKo}</span>
                            : i.status === 'PENDING' && !failed
                            ? <span className="text-xs text-muted">GPT 만드는 중…</span>
                            : i.status === 'PENDING'
                            ? <div className="flex items-center gap-2 text-xs">
                                <span className="text-orange">GPT 실패</span>
                                <button type="button" disabled={generate.isPending || find.isPending} onClick={() => generate.mutate()} className="text-primary hover:underline disabled:opacity-50">다시 만들기</button>
                              </div>
                            : <div className="flex flex-col gap-1">
                                <div className="flex items-center gap-1.5">
                                  <input aria-label={`${i.src} 최종 표기`} value={d.ko} onChange={(e) => setKo(i, e.target.value)}
                                    className="w-40 rounded-lg border border-line bg-white px-2 py-1 text-[13px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/10" />
                                  {gpt && <span title={i.gptReason ?? undefined}><Chip color="purple">GPT</Chip></span>}
                                </div>
                                {failed && (
                                  <div className="flex items-center gap-2 text-xs">
                                    <span className="text-orange">GPT 실패</span>
                                    <button type="button" disabled={generate.isPending || find.isPending} onClick={() => generate.mutate()} className="text-primary hover:underline disabled:opacity-50">다시 만들기</button>
                                  </div>
                                )}
                              </div>}
                        </td>
                        <td className="px-2 py-2">
                          {approved ? null : singleChar(i)
                            ? <span className="text-xs text-muted">한 글자 — 칸 전체가 같을 때만 적용</span>
                            : <div className="flex flex-col gap-0.5">
                                {others.map((r) => (
                                  <label key={r.ko} className="flex cursor-pointer items-center gap-1.5 whitespace-nowrap text-[13px]">
                                    <input type="checkbox" checked={!d.keep.has(r.ko)} onChange={() => toggleKeep(i, r.ko)} />
                                    {r.ko} {r.rows.toLocaleString()}행
                                  </label>
                                ))}
                              </div>}
                        </td>
                        <td className="px-2 py-2">
                          <div className="flex items-center gap-1.5">
                            {approved
                              ? <Chip color="green">승인됨</Chip>
                              : <Button className="px-2.5 py-1 text-[13px]" disabled={ko === '' || i.status === 'PENDING' || approve.isPending}
                                  onClick={() => approve.mutate([payloadOf(i)])}>승인</Button>}
                          </div>
                        </td>
                      </tr>
                      {open.has(i.id) && (
                        <tr className="border-b border-lineSoft bg-canvas">
                          <td colSpan={COLS} className="px-2 py-2 pl-[136px]">
                            {i.renderings.length === 0
                              ? <p className="py-1 text-xs text-muted">번역본에서 이 원문이 든 예문을 찾지 못했습니다.</p>
                              : i.renderings.map((r) => (
                                <div key={r.ko} className="flex gap-4 border-b border-dashed border-line py-1.5 last:border-b-0">
                                  <div className="w-40 shrink-0">
                                    <span className="font-semibold">{r.ko}</span>
                                    <span className="ml-2 text-xs tabular-nums text-muted">{r.rows.toLocaleString()}행</span>
                                  </div>
                                  <div className="min-w-0">
                                    <div className="break-all text-body">{mark(r.exampleSrc, i.src)}</div>
                                    <div className="break-keep">{mark(r.exampleKo, r.ko)}</div>
                                  </div>
                                </div>
                              ))}
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          )}
      </main>
    </div>
  )
}
