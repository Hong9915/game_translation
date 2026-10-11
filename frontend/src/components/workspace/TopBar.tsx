import { Link } from 'react-router-dom'
import type { PresenceEntry, ReviewerRef } from '../../api/types'
import type { ConnStatus } from '../../hooks/useWorkspaceEvents'
import { Button } from '../ui/Button'

interface TopBarProps {
  fileName: string; gameName: string; gameId: number; projectId: number; reviewer: ReviewerRef | null; presence: PresenceEntry[]; status: ConnStatus
  exporting: boolean; glossaryOpen: boolean; activityOpen: boolean; onRename: () => void; onExport: () => void; onToggleGlossary: () => void
  onToggleActivity: () => void
}

export function TopBar({ fileName, gameName, gameId, projectId, reviewer, presence, status, exporting, glossaryOpen, activityOpen, onRename, onExport, onToggleGlossary, onToggleActivity }: TopBarProps) {
  return (
    <div className="flex items-center gap-3 border-b border-line bg-white px-3 py-1.5">
      <Link to={`/games/${gameId}`} className="text-xs text-muted hover:underline">{gameName}</Link>
      <span className="text-xs text-muted">›</span>
      <span className="text-sm font-semibold">{fileName}</span>
      {/* 열을 잘못 지정했을 때 돌아갈 길(설계 §1) — 내보내기만큼 강조되지 않는 작은 링크 */}
      <Link to={`/normalize/${projectId}`} className="text-xs text-muted hover:underline">정규화</Link>
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
      <Button variant={glossaryOpen ? 'purple' : 'secondary'} onClick={onToggleGlossary}>단어장</Button>
      <Button variant={activityOpen ? 'purple' : 'secondary'} onClick={onToggleActivity}>변경 내역</Button>
      <Button variant="primary" onClick={onExport} disabled={exporting}>{exporting ? '만드는 중…' : '내보내기'}</Button>
    </div>
  )
}
