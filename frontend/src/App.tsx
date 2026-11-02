import { Route, Routes } from 'react-router-dom'
import { GamePage } from './pages/GamePage'
import { GamesPage } from './pages/GamesPage'
import { GlossaryRecommendPage } from './pages/GlossaryRecommendPage'
import { NormalizePage } from './pages/NormalizePage'
import { TermRequestPage } from './pages/TermRequestPage'
import { WorkspacePage } from './pages/WorkspacePage'

/** 검수 전용 앱: 게임 고르기 → 파일 올리기 → 정규화 → 엑셀식 검수. 화면마다 자기 상단 바를 갖는다. */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<GamesPage />} />
      <Route path="/games/:gameId" element={<GamePage />} />
      <Route path="/games/:gameId/glossary/recommend" element={<GlossaryRecommendPage />} />
      <Route path="/games/:gameId/glossary/requests/:requestId" element={<TermRequestPage />} />
      <Route path="/games/:gameId/review" element={<WorkspacePage />} />
      <Route path="/normalize/:id" element={<NormalizePage />} />
      <Route path="/review/:id" element={<WorkspacePage />} />
    </Routes>
  )
}
