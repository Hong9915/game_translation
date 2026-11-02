import { useEffect, useState } from 'react'
import { Button } from '../ui/Button'

export interface ExportPick { overwriteKo: boolean; finalOnly: boolean }

interface Props {
  fileName: string
  /** Fix를 새 열로 정한 시트가 있어 "기존 열에 덮어쓰기"를 고를 수 있는지. */
  canOverwrite: boolean
  /** 최종 리뷰 시트가 있으면 최종 수정 표시를 켠 행 수, 없으면 null — 그때만 "최종 수정분만"을 묻는다. */
  finalMarked: number | null
  finalFileName: string | null
  onPick: (pick: ExportPick) => void
  onClose: () => void
}

/**
 * 내보내기 방식 고르기(2026-10-13). Fix를 새 열로 정한 시트가 있으면 이번 파일만 기존 한국어 열에 덮어쓸 수 있다 — 정규화 설정은 바뀌지 않는다.
 * 최종 리뷰 시트가 있으면 게임사에 줄 "최종 수정분만"(최종 수정 표시를 켠 행만, 2026-10-19)도 여기서 고른다.
 */
export function ExportModeDialog({ fileName, canOverwrite, finalMarked, finalFileName, onPick, onClose }: Props) {
  const [finalOnly, setFinalOnly] = useState(false)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])
  const name = finalOnly && finalFileName ? finalFileName : fileName
  const choice = 'rounded-xl border px-3 py-2.5 text-left focus-visible:border-primary focus-visible:outline-none'
  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30" role="dialog" aria-modal="true" aria-label="내보내기 방식"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="pop-in w-[26rem] rounded-2xl bg-white p-5 shadow-2xl">
        {finalMarked !== null && (
          <>
            <h2 className="text-[15px] font-semibold text-ink">무엇을 내보낼까요?</h2>
            <div className="mt-3 grid grid-cols-2 gap-2" role="radiogroup" aria-label="내보낼 범위">
              <button type="button" role="radio" aria-checked={!finalOnly} onClick={() => setFinalOnly(false)}
                className={`${choice} ${!finalOnly ? 'border-primary bg-primarySoft' : 'border-line hover:border-primary'}`}>
                <span className="block text-[13px] font-medium text-ink">전체</span>
                <span className="block text-xs text-muted">모든 행을 채운 파일</span>
              </button>
              <button type="button" role="radio" aria-checked={finalOnly} disabled={finalMarked === 0} onClick={() => setFinalOnly(true)}
                className={`${choice} disabled:cursor-not-allowed disabled:opacity-50 ${finalOnly ? 'border-primary bg-primarySoft' : 'border-line enabled:hover:border-primary'}`}>
                <span className="block text-[13px] font-medium text-ink">최종 수정분만</span>
                <span className="block text-xs text-muted">{finalMarked === 0 ? '최종 수정 표시한 행이 없습니다' : `최종 수정 표시한 ${finalMarked.toLocaleString()}행만 · 원본과 같은 열`}</span>
              </button>
            </div>
          </>
        )}
        {canOverwrite ? (
          <>
            <h2 className={`${finalMarked !== null ? 'mt-5 text-[13px]' : 'text-[15px]'} font-semibold text-ink`}>Fix를 어디에 쓸까요?</h2>
            <p className="mt-1 break-keep text-xs text-body">
              정규화에서 Fix를 새 열로 정했습니다. 이번 파일({name})만 기존 한국어 열에 덮어쓸 수도 있습니다. 정규화 설정은 그대로입니다.
            </p>
            <div className="mt-4 grid gap-2">
              <button type="button" autoFocus onClick={() => onPick({ overwriteKo: false, finalOnly })}
                className="rounded-xl border border-line px-3 py-2.5 text-left hover:border-primary hover:bg-primarySoft focus-visible:border-primary focus-visible:outline-none">
                <span className="block text-[13px] font-medium text-ink">새 열로 추가</span>
                <span className="block text-xs text-muted">맨 끝 KR_Fix 열에 쓰고 기존 한국어 열은 그대로 둡니다</span>
              </button>
              <button type="button" onClick={() => onPick({ overwriteKo: true, finalOnly })}
                className="rounded-xl border border-line px-3 py-2.5 text-left hover:border-primary hover:bg-primarySoft focus-visible:border-primary focus-visible:outline-none">
                <span className="block text-[13px] font-medium text-ink">기존 한국어 열에 덮어쓰기</span>
                <span className="block text-xs text-muted">새 열을 만들지 않고 원래 한국어 칸에 Fix를 씁니다 · 바뀐 칸은 연노랑</span>
              </button>
            </div>
            <div className="mt-4 flex justify-end">
              <Button onClick={onClose}>취소</Button>
            </div>
          </>
        ) : (
          <>
            <p className="mt-3 break-keep text-xs text-body">{name}</p>
            <div className="mt-4 flex justify-end gap-2">
              <Button onClick={onClose}>취소</Button>
              <Button variant="primary" autoFocus onClick={() => onPick({ overwriteKo: false, finalOnly })}>내보내기</Button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
