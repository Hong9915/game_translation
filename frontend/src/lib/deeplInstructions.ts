import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api/client'

/** DeepL 한도(줄 10개, 줄당 300자). 서버(DeepLInstructions)도 같은 한도로 거절한다. */
export const MAX_LINES = 10
export const MAX_CHARS = 300

/** 빈 줄을 빼고 다듬은 지시 목록 — 서버의 DeepLInstructions.lines와 같은 규칙. */
const lines = (text: string) => text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
const normalized = (text: string) => lines(text).join('\n')

/** 한도를 넘으면 그 까닭, 아니면 null. */
export function instructionsProblem(text: string): string | null {
  const ls = lines(text)
  if (ls.length > MAX_LINES) return `지시문은 ${MAX_LINES}줄까지입니다.`
  const long = ls.findIndex((l) => l.length > MAX_CHARS)
  return long >= 0 ? `${long + 1}번째 줄이 ${MAX_CHARS}자를 넘습니다.` : null
}

/**
 * 번역 버튼 옆 DeepL 지시문(2026-10-26). 그 게임에 저장된 지시문을 보이고, 고친 글은 번역 전에 save로 게임에 저장한다 —
 * 지시문은 게임마다 하나라 다음 번역에도 그대로 쓰인다. 손대기 전(draft null)에는 저장된 글을 그대로 보인다.
 */
export function useDeepLInstructions(gameId: number | null | undefined) {
  const qc = useQueryClient()
  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId as number), enabled: gameId != null })
  const saved = game.data?.deeplInstructions ?? ''
  const [draft, setDraft] = useState<string | null>(null)
  const text = draft ?? saved
  const changed = normalized(text) !== normalized(saved)
  /** 바뀌었으면 저장한다. 한도를 넘으면 던진다. */
  const save = async () => {
    const problem = instructionsProblem(text)
    if (problem) throw new Error(problem)
    if (gameId == null || !changed) return
    const next = await api.games.setDeepLInstructions(gameId, text)
    qc.setQueryData(['game', gameId], next)   // 다음에 연 창이 저장한 글을 보이게
  }
  return { text, setText: setDraft, changed, save, loading: game.isLoading }
}
