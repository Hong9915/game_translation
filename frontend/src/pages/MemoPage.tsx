import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { api } from '../api/client'
import MemoPanel from '../components/workspace/MemoPanel'

/** 리뷰 화면의 메모 버튼이 새 창으로 여는 화면. 그리드를 가리지 않고 따로 띄워 둔다. */
export default function MemoPage() {
  const gameId = Number(useParams().gameId)
  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId) }).data
  useEffect(() => { document.title = game ? `메모 — ${game.name}` : '메모' }, [game])
  return <MemoPanel key={gameId} gameId={gameId} gameName={game?.name} />
}
