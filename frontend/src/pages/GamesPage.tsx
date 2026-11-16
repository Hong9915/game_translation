import { useMutation, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Game } from '../api/types'
import { AppHeader, Progress } from '../components/ui/AppHeader'
import { Button } from '../components/ui/Button'
import { inputClass } from '../components/ui/Field'

const day = (iso: string) => iso.slice(0, 10)
const COLS = 'grid grid-cols-[minmax(0,1fr)_88px_200px_112px_16px] items-center gap-4'

function GameRow({ game }: { game: Game }) {
  return (
    <Link to={`/games/${game.id}`}
      className={`${COLS} min-h-16 border-b border-lineSoft px-5 py-3 text-ink transition-colors last:border-b-0 hover:bg-canvas`}>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="truncate text-[15px] font-semibold">{game.name}</span>
        <span className="text-xs text-body">
          {game.projects > 0 ? `메모리 ${game.memory.toLocaleString()}행` : '아직 파일 없음'}
        </span>
      </span>
      <span className="text-[13px] text-body">{game.projects > 0 ? `파일 ${game.projects.toLocaleString()}` : '—'}</span>
      <Progress done={game.confirmedRows ?? 0} total={game.reviewRows ?? 0} />
      <span className="text-[13px] tabular-nums text-body">{game.lastUploadedAt ? day(game.lastUploadedAt) : `${day(game.createdAt)} 만듦`}</span>
      <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-muted"><path d="M9 6l6 6-6 6" /></svg>
    </Link>
  )
}

export function GamesPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [query, setQuery] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const games = useQuery({ queryKey: ['games'], queryFn: api.games.list })
  const usage = useQuery({ queryKey: ['usage-all'], queryFn: api.usageAll, retry: false, staleTime: 60_000 })
  const create = useMutation({
    mutationFn: (n: string) => api.games.create(n),
    onSuccess: (g) => navigate(`/games/${g.id}`),
    onError: (e: Error) => setError(e.message),
  })
  const submit = (e: FormEvent) => { e.preventDefault(); if (name.trim()) create.mutate(name.trim()) }
  const u = usage.data
  const all = games.data ?? []
  const empty = games.data?.length === 0
  const q = query.trim().toLowerCase()
  const shown = q ? all.filter((g) => g.name.toLowerCase().includes(q)) : all
  // 게임이 없으면 만들 칸을 처음부터 연다
  const formOpen = creating || empty

  return (
    <div className="flex min-h-screen flex-col">
      <AppHeader right={u && (
        <span className="text-xs text-body">
          {u.priced
            ? `누적 GPT 비용 $${u.costUsd.toFixed(2)} · 호출 ${u.calls.toLocaleString()}회`
            : `누적 GPT 토큰 ${(u.promptTokens + u.completionTokens).toLocaleString()} · 호출 ${u.calls.toLocaleString()}회`}
        </span>
      )} />

      <main className="mx-auto flex w-full max-w-[960px] flex-col gap-5 px-6 pb-16 pt-10">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-[28px] font-bold leading-tight tracking-[-0.02em]">게임</h1>
            <p className="text-[13px] text-body">게임마다 번역 메모리와 단어장을 따로 씁니다.</p>
          </div>
          {!empty && (
            <div className="flex items-center gap-2">
              <label className="flex h-[38px] w-56 items-center gap-2 rounded-lg border border-line bg-white px-3 text-muted focus-within:border-primary">
                <svg aria-hidden width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
                <input aria-label="게임 찾기" placeholder="게임 찾기" value={query} onChange={(e) => setQuery(e.target.value)}
                  className="w-full bg-transparent text-[13px] text-ink outline-none placeholder:text-muted" />
              </label>
              <Button variant="primary" aria-expanded={formOpen} onClick={() => { setCreating((v) => !v); setError(null) }}>+ 새 게임</Button>
            </div>
          )}
        </div>

        {formOpen && (
          <form onSubmit={submit} className="flex flex-col gap-2 rounded-xl border border-line/70 bg-white p-4 shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
            <div className="flex items-center gap-2">
              <input aria-label="새 게임 이름" autoFocus={!empty} className={`${inputClass} flex-1`} value={name}
                onChange={(e) => { setName(e.target.value); setError(null) }}
                onKeyDown={(e) => { if (e.key === 'Escape' && !empty) setCreating(false) }}
                placeholder="새 게임 이름 — 예: 드래곤 레이드" />
              <Button type="submit" variant="primary" disabled={!name.trim() || create.isPending}>게임 만들기</Button>
              {!empty && <Button onClick={() => setCreating(false)}>취소</Button>}
            </div>
            {error && <p className="text-xs text-orange">{error}</p>}
          </form>
        )}

        {empty
          ? <p className="py-8 text-center text-sm text-body">아직 게임이 없습니다. 첫 게임을 만들어 파일을 올리세요.</p>
          : (
            <section aria-label="게임 목록" className="overflow-hidden rounded-xl border border-line/70 bg-white shadow-[0_1px_2px_rgba(16,24,40,0.04)]">
              <div className={`${COLS} border-b border-lineSoft bg-tableHead px-5 py-2.5 text-xs text-body`}>
                <span>이름</span><span>파일</span><span>확정 진행</span><span>마지막 업로드</span><span />
              </div>
              {shown.map((g) => <GameRow key={g.id} game={g} />)}
              {games.data && shown.length === 0 && <p className="px-5 py-6 text-center text-[13px] text-body">「{query}」에 맞는 게임이 없습니다.</p>}
            </section>
          )}
      </main>
    </div>
  )
}
