import { useMutation, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Game } from '../api/types'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { inputClass } from '../components/ui/Field'
import { PageHeader } from '../components/ui/PageHeader'

const day = (iso: string) => iso.slice(0, 10)

function GameCard({ game }: { game: Game }) {
  return (
    <Link to={`/games/${game.id}`} className="lift press flex flex-col gap-2 rounded-xl border border-line/70 bg-white p-4 shadow-[0_1px_2px_rgba(16,24,40,0.04)] hover:-translate-y-0.5 hover:shadow-[0_8px_24px_-8px_rgba(16,24,40,0.18)]">
      <span className="text-base font-semibold">{game.name}</span>
      {game.projects > 0
        ? <span className="text-xs text-muted">파일 {game.projects.toLocaleString()} · 메모리 {game.memory.toLocaleString()}행</span>
        : <span className="text-xs text-muted">아직 파일 없음</span>}
      <span className="text-[11px] text-muted">{game.lastUploadedAt ? `마지막 업로드 ${day(game.lastUploadedAt)}` : `만든 날 ${day(game.createdAt)}`}</span>
    </Link>
  )
}

export function GamesPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
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

  return (
    <div className="mx-auto flex min-h-screen max-w-[1100px] flex-col gap-4 px-10 pt-9">
      <PageHeader title="게임 선택" description="게임마다 번역 메모리를 따로 쌓습니다. 게임을 고른 뒤 그 안에서 파일이나 폴더를 올리세요." />
      {u && (
        <p className="text-xs text-muted">
          {u.priced
            ? `누적 GPT 비용 $${u.costUsd.toFixed(2)} · 호출 ${u.calls.toLocaleString()}회`
            : `누적 GPT 토큰 ${(u.promptTokens + u.completionTokens).toLocaleString()} · 호출 ${u.calls.toLocaleString()}회`}
        </p>
      )}
      <Card className="p-5">
        <form onSubmit={submit} className="flex items-end gap-2">
          <label className="flex flex-1 flex-col gap-1.5 text-xs text-muted">
            <span>새 게임 이름</span>
            <input aria-label="새 게임 이름" className={inputClass} value={name} onChange={(e) => { setName(e.target.value); setError(null) }} placeholder="예: 드래곤 레이드" />
          </label>
          <Button type="submit" variant="primary" disabled={!name.trim() || create.isPending}>게임 만들기</Button>
        </form>
        {error && <p className="mt-2 text-xs text-orange">{error}</p>}
      </Card>
      {games.data?.length === 0
        ? <p className="py-8 text-center text-sm text-muted">아직 게임이 없습니다. 첫 게임을 만들어 파일을 올리세요.</p>
        : <div className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-3">
            {(games.data ?? []).map((g) => <GameCard key={g.id} game={g} />)}
          </div>}
    </div>
  )
}
