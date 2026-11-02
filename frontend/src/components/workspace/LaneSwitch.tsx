/** 게임 전체 리뷰의 레인 전환(설계 2026-10-17): 일반 파일과 최종 리뷰 파일을 따로 본다. 두 레인이 다 있을 때만 그린다. */
export type Lane = 'final' | 'normal'

export function LaneSwitch({ lane, onChange }: { lane: Lane; onChange: (l: Lane) => void }) {
  return (
    <div role="radiogroup" aria-label="레인" className="mb-1 mr-2 flex overflow-hidden rounded border border-line text-xs">
      {(['normal', 'final'] as const).map((l) => (
        <label key={l} className={`cursor-pointer px-2 py-0.5 ${lane === l ? 'bg-ink text-white' : 'bg-white text-muted hover:text-ink'}`}>
          <input type="radio" name="lane" className="sr-only" checked={lane === l} onChange={() => onChange(l)} />
          {l === 'final' ? '최종 리뷰' : '일반'}
        </label>
      ))}
    </div>
  )
}
