import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api/client'

/** 덧붙임 글자 한도. 서버(GptInstructions.MAX_EXTRA)도 같은 한도로 거절한다. */
export const MAX_EXTRA = 2000

/**
 * 게임별 GPT 지시문. base는 서버의 기본 지시문(읽기 전용), text는 그 게임이 덧붙인 글.
 * 고친 글은 save로 게임에 저장한다. 손대기 전(draft null)에는 저장된 글을 그대로 보인다.
 */
export function useGptInstructions(gameId: number | null | undefined, which: 'translate' | 'polish') {
  const qc = useQueryClient()
  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId as number), enabled: gameId != null })
  const base = (which === 'translate' ? game.data?.gptTranslateBase : game.data?.gptPolishBase) ?? ''
  const saved = (which === 'translate' ? game.data?.gptTranslateExtra : game.data?.gptPolishExtra) ?? ''
  const [draft, setDraft] = useState<string | null>(null)
  const text = draft ?? saved
  const changed = text.trim() !== saved.trim()
  /** 바뀌었으면 저장한다. 한도를 넘으면 던진다. */
  const save = async () => {
    if (text.trim().length > MAX_EXTRA) throw new Error(`덧붙인 지시는 ${MAX_EXTRA}자까지 됩니다.`)
    if (gameId == null || !changed) return
    const next = await api.games.setGptInstructions(gameId, { [which]: text })
    qc.setQueryData(['game', gameId], next)
    setDraft(null)
  }
  return { base, text, setText: setDraft, changed, save, loading: game.isLoading }
}
