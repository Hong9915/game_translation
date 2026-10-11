import type { CommandRun } from './commandRuns'

/** 끝났는데 아직 창에서 안 본 실행. */
const unseen = (run: CommandRun | null): run is CommandRun => !!run && run.status !== 'running' && !run.seen

/** 열 시트: 진행 중이거나 안 본 실행이 있으면 그 실행의 시트, 아니면 지금 시트. */
const target = (run: CommandRun | null, sheet: string) => (run && (run.status === 'running' || !run.seen) ? run.sheet : sheet)

/**
 * 리뷰 화면 도구줄의 "명령으로 고치기…" 버튼(2026-10-08). 창 밖에서 도는 실행을 이름으로 알린다:
 * 실행 중 → "명령 처리 중…", 안 본 결과 → "명령 결과 보기", 안 본 실패 → "명령 실패 · 보기". 다른 시트의 실행이면 시트 이름을 붙인다.
 */
export function CommandRunButton({ run, sheet, onOpen }: { run: CommandRun | null; sheet: string; onOpen: (sheet: string) => void }) {
  const other = run && run.sheet !== sheet ? ` · ${run.sheet}` : ''
  const label = run?.status === 'running' ? `명령 처리 중…${other}`
    : unseen(run) ? (run.status === 'error' ? `명령 실패 · 보기${other}` : `명령 결과 보기${other}`)
    : '명령으로 고치기…'
  const active = run?.status === 'running' || unseen(run)
  return (
    <button type="button" onClick={() => onOpen(target(run, sheet))} title="할 일을 적으면 GPT가 찾아 바꾸기 규칙으로 바꾸고, 바뀌는 행을 골라서 고칩니다. 창을 닫아도 계속 돕니다"
      className={`press mb-1 inline-flex items-center gap-1.5 whitespace-nowrap rounded border px-2 py-0.5 text-xs hover:bg-canvas ${active ? 'border-primary bg-[#eef3fb] text-primary' : 'border-line bg-white text-body'}`}>
      {run?.status === 'running' && <span aria-hidden className="h-2 w-2 animate-pulse rounded-full bg-primary" />}
      {unseen(run) && <span aria-hidden className={`h-2 w-2 rounded-full ${run.status === 'error' ? 'bg-orange' : 'bg-[#16a34a]'}`} />}
      {label}
    </button>
  )
}

/** 창이 닫혀 있을 때 실행이 끝나면 화면 아래에 알린다. "보기"로 그 실행의 시트 창을 연다. */
export function CommandRunNotice({ run, dialogOpen, onOpen }: { run: CommandRun | null; dialogOpen: boolean; onOpen: (sheet: string) => void }) {
  if (dialogOpen || !unseen(run)) return null
  return (
    <div role="status" className="fade-in fixed bottom-10 right-4 z-40 flex items-center gap-3 rounded-xl bg-ink px-4 py-2.5 text-[13px] text-white shadow-lg">
      {run.status === 'error' ? `명령이 실패했습니다 · ${run.sheet}` : `명령 결과가 준비됐습니다 · ${run.sheet}`}
      <button type="button" className="rounded-md bg-white/15 px-2 py-0.5 font-medium hover:bg-white/25" onClick={() => onOpen(run.sheet)}>보기</button>
    </div>
  )
}
