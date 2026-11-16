import { Link } from 'react-router-dom'
import type { PresenceEntry, ReviewerRef } from '../../api/types'
import type { ConnStatus } from '../../hooks/useWorkspaceEvents'
import { Button } from '../ui/Button'

interface TopBarProps {
  fileName: string; gameName: string; gameId: number
  /** 파일 화면이면 정규화 링크를 보인다. 게임 전체 리뷰 화면은 파일이 여럿이라 없다. */
  projectId?: number
  reviewer: ReviewerRef | null; presence: PresenceEntry[]; status: ConnStatus
  exporting: boolean; glossaryOpen: boolean; activityOpen: boolean; onRename: () => void; onExport: () => void; onToggleGlossary: () => void
  onToggleActivity: () => void
  /** 내보내기 버튼 글·막기·설명. 게임 화면의 "이 시트 내보내기"는 이 시트만 / 전체 파일 zip을 고르는 창을 연다. */
  exportLabel?: string; exportDisabled?: boolean; exportTitle?: string
}

export function TopBar({ fileName, gameName, gameId, projectId, reviewer, presence, status, exporting, glossaryOpen, activityOpen, onRename, onExport, onToggleGlossary, onToggleActivity,
  exportLabel = '내보내기', exportDisabled = false, exportTitle }: TopBarProps) {
  return (
    <div className="flex items-center gap-3 whitespace-nowrap border-b border-line bg-white px-3 py-1.5">
      <Link to={`/games/${gameId}`} className="text-xs text-muted hover:underline">{gameName}</Link>
      <span className="text-xs text-muted">›</span>
      <span className="min-w-0 truncate text-sm font-semibold" title={fileName}>{fileName}</span>
      {/* 열을 잘못 지정했을 때 돌아갈 길(설계 §1) — 내보내기만큼 강조되지 않는 작은 링크 */}
      {projectId !== undefined && <Link to={`/normalize/${projectId}`} className="text-xs text-muted hover:underline">정규화</Link>}
      <div className="flex-1" />
      <span className="text-[11px] text-muted" title={status === 'open' ? '실시간 연결됨' : status === 'lost' ? '연결 끊김 — 다시 연결 중' : '연결 중'}>
        <span className={`mr-1 inline-block h-2 w-2 rounded-full ${status === 'open' ? 'bg-green' : 'bg-muted'}`} />
        접속 {presence.length + (reviewer ? 1 : 0)}{presence.length > 0 && ` — ${presence.map((p) => p.name).join(' · ')}`}
      </span>
      {reviewer && (
        // 누르면 이름 모달이 열린다 — 드롭다운이 아니므로 ▾를 붙이지 않는다
        <button type="button" onClick={onRename} title="이름 바꾸기" className="press flex items-center gap-1.5 rounded-full border border-line px-3 py-1 text-xs hover:bg-canvas">
          <span className="inline-block h-2 w-2 rounded-full" style={{ background: reviewer.color }} />{reviewer.name}
        </button>
      )}
      {/* 게임마다 창 이름이 같아서 다시 눌러도 창이 늘지 않고 열려 있던 창으로 간다. gameId 0은 아직 불러오는 중. */}
      <Button disabled={gameId === 0} title="새 창으로 메모 열기" onClick={() => window.open(`/games/${gameId}/memo`, `memo-${gameId}`, 'popup,width=720,height=820')?.focus()}>메모</Button>
      <Button disabled={gameId === 0} title="손 수정에서 배운 교정 규칙을 새 탭에서 보기" onClick={() => window.open(`/games/${gameId}/rules`, `rules-${gameId}`)?.focus()}>교정 규칙</Button>
      <Button variant={glossaryOpen ? 'purple' : 'secondary'} onClick={onToggleGlossary}>단어장</Button>
      <Button variant={activityOpen ? 'purple' : 'secondary'} onClick={onToggleActivity}>변경 내역</Button>
      <Button variant="primary" onClick={onExport} disabled={exporting || exportDisabled} title={exportTitle}>{exporting ? '만드는 중…' : exportLabel}</Button>
    </div>
  )
}
