import { useTranslateJob } from '../../hooks/useTranslateJob'

/** 리뷰 화면 위 DeepL 번역 진행 막대. 작업이 없으면 아무것도 그리지 않는다. */
export function TranslateJobBar({ projectId, cacheKey }: { projectId: number; cacheKey: number | string }) {
  const job = useTranslateJob(projectId, cacheKey)
  if (job.running) {
    const pct = job.total > 0 ? Math.round((job.done / job.total) * 100) : 0
    return (
      <div role="status" aria-label="DeepL 번역 진행" className="flex items-center gap-3 rounded-md border border-line bg-white px-3 py-2 text-xs">
        <span className="font-medium">DeepL 번역 중…</span>
        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-lineSoft"><div className="h-full bg-primary transition-[width]" style={{ width: `${pct}%` }} /></div>
        <span className="tabular-nums text-muted">{job.done}/{job.total} 묶음</span>
      </div>
    )
  }
  if (!job.summary) return null
  return (
    <div role="status" className="flex items-center justify-between rounded-md border border-line bg-canvas px-3 py-2 text-xs">
      <span>{job.summary} — "확인 필요" 필터로 태그·중국어 문제가 있는 행을 볼 수 있습니다.</span>
      <button type="button" onClick={job.dismiss} className="text-muted hover:text-ink" aria-label="닫기">✕</button>
    </div>
  )
}
