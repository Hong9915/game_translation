import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, api } from '../../api/client'
import type { GlossaryEntry, GlossaryKind, GlossaryMatchMode, GlossaryRequest, GlossarySuggestResult } from '../../api/types'
import { Button } from '../ui/Button'
import { Field, inputClass } from '../ui/Field'

type Scope = 'GAME' | 'SHARED'
type OldKoMode = 'CANDIDATE' | 'CUSTOM' | 'NONE'

export interface GlossaryPanelProps {
  gameId: number
  projectId: number
  onClose: () => void
  initial?: { kind: GlossaryKind; find: string; matchMode: GlossaryMatchMode; seq: number }
}

const NOTICE_MS = 4000
const KIND_LABEL: Record<GlossaryKind, string> = { SRC: '중국어(간체) → 한국어', KO: '한국어 기존 → 바뀐' }
const MODE_LABEL: Record<GlossaryMatchMode, string> = { WHOLE: '통째로 같을 때', PARTIAL: '들어 있으면' }
const SRC_PARTIAL_NOTE = '자동 치환은 안 됩니다. 용어 불일치 표시와 번역 지시로만 쓰입니다.'
const ONE_CHAR_NOTE = '한 글자는 너무 많은 문장에 걸립니다. 두 글자 이상을 권합니다.'
// SRC 항목은 저장만으로는 용어 불일치 표시가 바뀌지 않는다(대개 0행 반영됨) — 어떻게 갱신하는지 알려 준다.
// 불일치 다시 계산 버튼은 막혀 있다(SheetBar의 RECOMPUTE_DISABLED_REASON) — 버튼을 누르라고 안내하지 않는다.
const SRC_RECOMPUTE_HINT = '이미 있는 행의 용어 불일치 표시는 바로 바뀌지 않습니다. 새로 번역하거나 저장한 행부터 반영됩니다.'

function toMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : '요청에 실패했습니다.'
}

/** 검수 워크스페이스 오른쪽 단어장 패널(설계 §3·Task 11) — 게임 전용·공유 단어장을 보고 고치고 일괄 적용한다. */
export default function GlossaryPanel({ gameId, projectId, onClose, initial }: GlossaryPanelProps) {
  const qc = useQueryClient()
  const [scope, setScope] = useState<Scope>('GAME')
  const [editing, setEditing] = useState<GlossaryEntry | null>(null)
  const [kind, setKind] = useState<GlossaryKind>('SRC')
  const [matchMode, setMatchMode] = useState<GlossaryMatchMode>('WHOLE')
  const [find, setFind] = useState('')
  const [ko, setKo] = useState('')
  const [note, setNote] = useState('')
  const [count, setCount] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [noticeHint, setNoticeHint] = useState<string | null>(null)
  const [noticeSeq, setNoticeSeq] = useState(0)
  const [search, setSearch] = useState('')
  // 삭제 확인 중인 항목. 다른 항목의 삭제를 누르면 그쪽으로 옮겨 간다.
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null)
  // SRC+PARTIAL의 기존 표기(설계 2026-09-29 §3). 후보에서 고르거나(CANDIDATE) 직접 쓰거나(CUSTOM) 없음(NONE: 감시 전용).
  const [oldKoMode, setOldKoMode] = useState<OldKoMode>('CANDIDATE')
  const [oldKoPick, setOldKoPick] = useState('')
  const [oldKoCustom, setOldKoCustom] = useState('')
  const [suggest, setSuggest] = useState<GlossarySuggestResult | null>(null)
  const [suggesting, setSuggesting] = useState(false)

  const srcPartial = kind === 'SRC' && matchMode === 'PARTIAL'
  const effectiveOldKo: string | null = !srcPartial ? null
    : oldKoMode === 'CANDIDATE' ? (oldKoPick || null)
    : oldKoMode === 'CUSTOM' ? (oldKoCustom.trim() || null)
    : null
  // 새 항목은 첫 후보를 미리 고른다. 이미 있는 항목을 열 때는 저장된 값이 없으면 "없음"으로 연다 — 감시 전용으로 둔 항목의
  // 비고만 고쳐 저장했는데 첫 후보가 몰래 선택되어 수백 행이 바뀌는 일을 막는다(리뷰 Important 3).
  const resetOldKo = (saved: string | null, editingEntry: boolean) => {
    setSuggest(null); setSuggesting(false); setOldKoPick('')
    setOldKoCustom(saved ?? '')
    setOldKoMode(saved ? 'CUSTOM' : editingEntry ? 'NONE' : 'CANDIDATE')
  }
  // 찾을 말이 바뀌면 이전 용어의 후보 선택을 바로 푼다 — 새 후보가 오기 전에 저장해도 옛 표기가 새 용어에 실리지 않게(리뷰 Important 4).
  const lastSuggestFind = useRef('')

  // 셀 우클릭 → 단어장에 추가가 채워 주는 시작값(Task 12). seq에만 의존한다 — initial은 부모가 렌더될 때마다
  // 새 오브젝트일 수 있는데, 그때마다 이 effect가 돌면 사용자가 입력 중인 값을 덮어써 버린다. seq는 실제로
  // "셀에서 또 골랐을 때"만 늘어나므로, 같은 셀을 두 번 골라도(같은 값이라도) 다시 채워진다.
  useEffect(() => {
    if (!initial) return
    setEditing(null)
    setKind(initial.kind)
    setMatchMode(initial.matchMode)
    setFind(initial.find)
    setKo('')
    setNote('')
    setScope('GAME')
    resetOldKo(null, false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial?.seq])

  useEffect(() => {
    if (noticeSeq === 0) return
    const t = setTimeout(() => { setNotice(null); setNoticeHint(null) }, NOTICE_MS)
    return () => clearTimeout(t)
  }, [noticeSeq])

  const gameList = useQuery({ queryKey: ['glossary', gameId, 'GAME'], queryFn: () => api.glossary.list(gameId, 'GAME') })
  const sharedList = useQuery({ queryKey: ['glossary', gameId, 'SHARED'], queryFn: () => api.glossary.list(gameId, 'SHARED') })
  const active = scope === 'GAME' ? gameList : sharedList
  const rows = [...(active.data ?? [])].sort((a, b) => a.find.localeCompare(b.find, 'ko'))

  const previewNote = srcPartial && !effectiveOldKo ? SRC_PARTIAL_NOTE
    : kind === 'KO' && matchMode === 'PARTIAL' && find.trim().length === 1 ? ONE_CHAR_NOTE
    : null

  // 건수 미리보기: find·ko가 모두 차 있을 때만, 300ms 디바운스로 부른다(Ruling 29).
  useEffect(() => {
    setCount(null)
    if (previewNote) return
    if (!find.trim() || !ko.trim()) return
    // 이 실행이 밀려났는지(더 최신 입력으로 effect가 다시 돌았는지) 표시 — 늦게 돌아온 응답이
    // 이미 지나간 입력값의 건수로 최신 건수를 덮어쓰지 않게 막는다.
    let cancelled = false
    const h = setTimeout(() => {
      api.glossary.count(gameId, { kind, matchMode, find: find.trim(), ko: ko.trim(), oldKo: effectiveOldKo })
        .then((r) => { if (!cancelled) setCount(r.count) })
        .catch((e) => { if (!cancelled) setError(toMessage(e)) })
    }, 300)
    return () => { cancelled = true; clearTimeout(h) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameId, kind, matchMode, find, ko, previewNote, effectiveOldKo])

  // 기존 표기 후보: SRC+PARTIAL이고 찾을 말이 2글자 이상일 때, 입력이 멈춘 뒤 300ms에 한 번. ko가 바뀌면 예시(after)만 달라진다.
  // 사용자가 이미 고른 후보가 새 결과에도 있으면 그대로 두고, 없으면 첫 후보로 옮긴다. 후보가 없으면 직접 입력으로 넘긴다.
  useEffect(() => {
    if (!srcPartial || find.trim().length < 2) { setSuggest(null); setSuggesting(false); return }
    if (lastSuggestFind.current !== find.trim()) { lastSuggestFind.current = find.trim(); setOldKoPick('') }
    let cancelled = false
    setSuggesting(true)
    const h = setTimeout(() => {
      api.glossary.suggest(gameId, { find: find.trim(), ko: ko.trim() })
        .then((r) => {
          if (cancelled) return
          setSuggest(r); setSuggesting(false)
          // 수정 중 저장된 값(직접 입력 칸)이 후보에 있으면 그 라디오로 옮긴다(설계 §3)
          const custom = oldKoCustom.trim()
          const customIsCandidate = custom !== '' && r.candidates.some((c) => c.oldKo === custom)
          setOldKoPick((prev) => (r.candidates.some((c) => c.oldKo === prev) ? prev : customIsCandidate ? custom : (r.candidates[0]?.oldKo ?? '')))
          setOldKoMode((prev) => {
            if (prev === 'CANDIDATE' && r.candidates.length === 0) return 'CUSTOM'
            if (prev === 'CUSTOM' && customIsCandidate) return 'CANDIDATE'
            return prev
          })
        })
        .catch((e) => {
          if (cancelled) return
          setSuggest({ rows: 0, candidates: [] }); setSuggesting(false)
          setOldKoMode((prev) => (prev === 'CANDIDATE' ? 'CUSTOM' : prev))
          setError(toMessage(e))
        })
    }, 300)
    return () => { cancelled = true; clearTimeout(h) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameId, srcPartial, find, ko])

  const invalidateGlossary = () => qc.invalidateQueries({ queryKey: ['glossary', gameId] })
  const invalidateRows = () => {
    qc.invalidateQueries({ queryKey: ['workspace-rows', projectId] })
    qc.invalidateQueries({ queryKey: ['workspace-summary', projectId] })
  }
  const showNotice = (text: string, hint: string | null = null) => {
    setNotice(text); setNoticeHint(hint); setNoticeSeq((s) => s + 1)
  }

  const resetForm = () => {
    setEditing(null); setKind('SRC'); setMatchMode('WHOLE'); setFind(''); setKo(''); setNote(''); setCount(null)
    resetOldKo(null, false)
  }
  const startEdit = (entry: GlossaryEntry) => {
    setEditing(entry); setKind(entry.kind); setMatchMode(entry.matchMode); setFind(entry.find)
    setKo(entry.ko ?? ''); setNote(entry.note ?? ''); setCount(null); setError(null)
    resetOldKo(entry.oldKo, true)
  }

  const save = useMutation({
    mutationFn: (body: GlossaryRequest) => (editing ? api.glossary.update(editing.id, body) : api.glossary.upsert(gameId, body)),
    onSuccess: (res, body) => {
      setError(null)
      invalidateGlossary(); invalidateRows()
      showNotice(editing?.shared && res.applied === 0
        ? '공유 단어집을 고쳤습니다. 각 게임에서 전체 다시 적용을 누르면 반영됩니다.'
        : `${res.applied}행 반영됨`, body.kind === 'SRC' ? SRC_RECOMPUTE_HINT : null)
      resetForm()
    },
    onError: (e) => setError(toMessage(e)),
  })
  const remove = useMutation({
    mutationFn: (id: number) => api.glossary.remove(id),
    onSuccess: (_d, id) => { setError(null); invalidateGlossary(); if (editing?.id === id) resetForm() },
    onError: (e) => setError(toMessage(e)),
    onSettled: () => setConfirmDeleteId(null),
  })
  const share = useMutation({
    mutationFn: (id: number) => api.glossary.share(id),
    onSuccess: () => { setError(null); invalidateGlossary() },
    onError: (e) => setError(toMessage(e)),
  })
  const applyAll = useMutation({
    mutationFn: () => api.glossary.applyAll(gameId),
    onSuccess: (res) => { setError(null); invalidateRows(); showNotice(`${res.applied}행 반영됨`) },
    onError: (e) => setError(toMessage(e)),
  })

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate({ kind, matchMode, find: find.trim(), ko: ko.trim() || null, note: note.trim() || null, oldKo: effectiveOldKo })
  }

  const q = search.trim().toLowerCase()
  const shown = q ? rows.filter((e) => [e.find, e.ko ?? '', e.note ?? ''].some((t) => t.toLowerCase().includes(q))) : rows

  return (
    <aside className="flex h-full w-[380px] flex-none flex-col border-l border-line bg-white text-sm text-ink">
      <div className="flex items-center gap-2 px-4 pb-2 pt-3">
        <h2 className="text-[15px] font-semibold">단어장</h2>
        <div className="flex-1" />
        {/* 추천은 게임 전체를 훑어 수천 줄이 나오므로 좁은 패널이 아니라 새 창의 표로 본다 */}
        <a href={`/games/${gameId}/glossary/recommend`} target="_blank" rel="noreferrer" title="단어장에 없는 반복 구절과 지금 번역을 새 창에서 본다"
          className="press rounded-lg border border-line bg-white px-2.5 py-1 text-xs font-medium text-ink hover:bg-canvas">추천 보기</a>
        <Button onClick={() => applyAll.mutate()} disabled={applyAll.isPending} className="!px-2.5 !py-1 !text-xs">전체 다시 적용</Button>
        <button type="button" aria-label="닫기" onClick={onClose} className="press flex h-7 w-7 items-center justify-center rounded-full text-base text-muted hover:bg-canvas">×</button>
      </div>
      {/* 두 목록을 오가는 분할 버튼. 탭 이름(aria-label)은 건수를 빼고 둔다. */}
      <div role="tablist" className="mx-4 grid grid-cols-2 rounded-lg bg-canvas p-0.5">
        {(['GAME', 'SHARED'] as const).map((s) => {
          const n = (s === 'GAME' ? gameList : sharedList).data?.length
          const label = s === 'GAME' ? '이번 게임' : '공유 단어집'
          return (
            <button key={s} type="button" role="tab" aria-label={label} aria-selected={scope === s} onClick={() => setScope(s)}
              className={`press rounded-md py-1 text-xs ${scope === s ? 'bg-white font-semibold text-ink shadow-[0_1px_2px_rgba(16,24,40,0.12)]' : 'text-muted hover:text-ink'}`}>
              {label}{n !== undefined && <span className="ml-1 font-normal text-muted">{n.toLocaleString()}</span>}
            </button>
          )
        })}
      </div>
      <div className="px-4 pb-2 pt-2">
        <input aria-label="단어장 검색" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="찾을 문자열·한국어·비고 검색"
          className="w-full rounded-lg border border-line bg-white px-3 py-1.5 text-[13px] outline-none transition-shadow focus:border-primary focus:ring-4 focus:ring-primary/10" />
      </div>
      {notice && (
        <p className="mx-4 mb-2 break-keep rounded-lg bg-greenSoft px-3 py-1.5 text-[11px] leading-relaxed text-green">
          {notice}
          {noticeHint && <span className="ml-1 text-muted">{noticeHint}</span>}
        </p>
      )}
      {error && <p role="alert" className="mx-4 mb-2 break-keep rounded-lg bg-orangeSoft px-3 py-1.5 text-[11px] leading-relaxed text-orange">{error}</p>}
      <div className="flex-1 overflow-auto border-t border-lineSoft px-2 py-1">
        {shown.length === 0 ? (
          <p className="px-2 py-8 text-center text-xs text-muted">{q ? '검색 결과가 없습니다.' : active.isLoading ? '불러오는 중…' : '아직 항목이 없습니다. 아래에서 추가하거나, 표의 칸을 우클릭하세요.'}</p>
        ) : (
          <ul>
            {shown.map((entry) => (
              <li key={entry.id} className={`flex items-start gap-2 rounded-lg px-2 py-2 ${editing?.id === entry.id ? 'bg-primarySoft' : 'hover:bg-canvas'}`}>
                <div className="min-w-0 flex-1">
                  {/* 원문(중국어)은 띄어쓰기가 없어 아무 데서나 끊고, 한국어는 낱말 단위로만 끊는다 */}
                  <div className="flex flex-wrap items-baseline gap-x-1.5 text-[13px] leading-snug">
                    <span className="break-all font-medium">{entry.find}</span>
                    {entry.oldKo && <span className="text-[11px] text-muted">(기존: {entry.oldKo})</span>}
                    <span className="text-muted">→</span>
                    {entry.ko ? <span className="break-keep">{entry.ko}</span> : <span className="text-xs text-muted">메모만</span>}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-1 text-[11px] tracking-[0.01em]">
                    <span title={KIND_LABEL[entry.kind]} className={`rounded px-1.5 py-px ${entry.kind === 'SRC' ? 'bg-purpleSoft text-purple' : 'bg-greenSoft text-green'}`}>
                      {entry.kind === 'SRC' ? '중→한' : '한→한'}
                    </span>
                    <span className="rounded bg-canvas px-1.5 py-px text-body">{entry.matchMode === 'WHOLE' ? '통째로' : '들어 있으면'}</span>
                    {entry.shared && <span className="rounded bg-primarySoft px-1.5 py-px text-primary">공유</span>}
                  </div>
                  {entry.note && <p className="mt-1 break-keep text-[11px] leading-relaxed text-muted">{entry.note}</p>}
                </div>
                {/* 삭제는 되돌릴 수 없고 "수정" 바로 옆이라, 줄 안에서 한 번 더 확인한다(브라우저 confirm()은 쓰지 않는다 — 앱의 다른 삭제와 같은 패턴) */}
                {confirmDeleteId === entry.id ? (
                  <div className="flex shrink-0 items-center gap-1 text-[11px]">
                    <button type="button" className="press rounded-md bg-[#c0392b] px-2 py-0.5 font-medium text-white hover:bg-[#a93226] disabled:opacity-60"
                      disabled={remove.isPending} onClick={() => remove.mutate(entry.id)}>정말 삭제</button>
                    <button type="button" className="press rounded-md border border-line bg-white px-2 py-0.5 text-body hover:bg-canvas"
                      disabled={remove.isPending} onClick={() => setConfirmDeleteId(null)}>취소</button>
                  </div>
                ) : (
                  <div className="flex shrink-0 items-center gap-0.5 text-[11px]">
                    <button type="button" className="press rounded-md px-1.5 py-0.5 text-primary hover:bg-primarySoft" onClick={() => startEdit(entry)}>수정</button>
                    <button type="button" className="press rounded-md px-1.5 py-0.5 text-orange hover:bg-orangeSoft" aria-label={`${entry.find} 삭제`}
                      onClick={() => setConfirmDeleteId(entry.id)}>삭제</button>
                    {!entry.shared && (
                      <button type="button" title="다른 게임에서도 쓰도록 공유 단어집으로 옮긴다" className="press rounded-md px-1.5 py-0.5 text-purple hover:bg-purpleSoft"
                        disabled={share.isPending && share.variables === entry.id} onClick={() => share.mutate(entry.id)}>공유로 올리기</button>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      <p className="break-keep border-t border-lineSoft px-4 py-1.5 text-[11px] text-muted">항목을 지워도 이미 바뀐 문장은 되돌아가지 않습니다.</p>
      <form onSubmit={onSubmit} className="flex flex-col gap-2 border-t border-line bg-canvas/60 px-4 pb-3 pt-2.5">
        <h3 className="truncate text-xs font-semibold text-body">{editing ? `고치는 중: ${editing.find}` : '새 항목'}</h3>
        <div className="grid grid-cols-2 gap-2">
          <Field label="종류">
            <select aria-label="종류" className={inputClass} value={kind} onChange={(e) => setKind(e.target.value as GlossaryKind)}>
              {(['SRC', 'KO'] as const).map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
            </select>
          </Field>
          <Field label="맞추기">
            <select aria-label="맞추기" className={inputClass} value={matchMode} onChange={(e) => setMatchMode(e.target.value as GlossaryMatchMode)}>
              {(['WHOLE', 'PARTIAL'] as const).map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field label="찾을 문자열">
            <input aria-label="찾을 문자열" className={inputClass} value={find} onChange={(e) => setFind(e.target.value)} />
          </Field>
          <Field label="쓸 한국어">
            <input aria-label="쓸 한국어" className={inputClass} value={ko} onChange={(e) => setKo(e.target.value)} />
          </Field>
        </div>
        {srcPartial && (
          <fieldset className="flex flex-col gap-1.5 rounded-lg border border-line bg-white px-3 py-2">
            <legend className="px-1 text-[11px] text-muted">기존 표기{suggesting && ' · 후보 찾는 중…'}</legend>
            {(suggest?.candidates ?? []).map((c) => (
              <label key={c.oldKo} className="flex items-start gap-2 text-[12px] leading-snug">
                <input type="radio" name="oldKo" aria-label={`기존 표기 ${c.oldKo}`} className="mt-0.5"
                  checked={oldKoMode === 'CANDIDATE' && oldKoPick === c.oldKo}
                  onChange={() => { setOldKoMode('CANDIDATE'); setOldKoPick(c.oldKo) }} />
                <span className="min-w-0">
                  <span className="font-medium">{c.oldKo}</span> <span className="text-muted">· </span><span className="text-muted">{c.rows}행</span>
                  <span className="block break-keep text-[11px] text-muted">{c.example.before} → {c.example.after ?? '…'}</span>
                </span>
              </label>
            ))}
            <label className="flex items-center gap-2 text-[12px]">
              <input type="radio" name="oldKo" aria-label="기존 표기 직접 입력" checked={oldKoMode === 'CUSTOM'} onChange={() => setOldKoMode('CUSTOM')} />
              <span className="shrink-0">직접 입력</span>
              <input aria-label="기존 표기" className={inputClass} value={oldKoCustom}
                onChange={(e) => { setOldKoCustom(e.target.value); setOldKoMode('CUSTOM') }} placeholder="지금 번역에 쓰인 말" />
            </label>
            <label className="flex items-center gap-2 text-[12px]">
              <input type="radio" name="oldKo" aria-label="기존 표기 없음" checked={oldKoMode === 'NONE'} onChange={() => setOldKoMode('NONE')} />
              없음(표시만)
            </label>
          </fieldset>
        )}
        <Field label="비고">
          <input aria-label="비고" className={inputClass} value={note} onChange={(e) => setNote(e.target.value)} placeholder="예: 버프와 헷갈리지 않게" />
        </Field>
        <div className="flex items-center gap-2">
          <Button type="submit" variant="primary" disabled={save.isPending}>{editing ? '저장' : '추가'}</Button>
          {editing && <Button type="button" onClick={resetForm}>취소</Button>}
          {(previewNote || count !== null) && <span className="break-keep text-[11px] text-muted">{previewNote ?? `대상 ${count}행`}</span>}
        </div>
      </form>
    </aside>
  )
}
