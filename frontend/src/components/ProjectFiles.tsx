import { api } from '../api/client'
import type { Project } from '../api/types'

/** 한 파일 줄을 펼쳤을 때 보이는 원본 다운로드. 차수별 수정본 기록은 쓰지 않는다. */
export function ProjectFiles({ project }: { project: Project }) {
  return (
    <div className="flex flex-col gap-1.5 text-[13px]" data-testid="project-files">
      <div className="flex items-center gap-3">
        <span className="w-24 shrink-0 font-semibold">원본</span>
        <a className="text-primary underline-offset-2 hover:underline" href={api.urls.original(project.id)} download>{project.fileName}</a>
        <span className="text-muted">{project.uploadedAt.slice(0, 10)} · {project.rowCount.toLocaleString()}행</span>
      </div>
      <p className="text-muted">고친 결과는 검수 화면의 "내보내기"로 받습니다.</p>
    </div>
  )
}
