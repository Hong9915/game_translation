import { useEffect, useRef, useState } from 'react'
import { api } from '../../api/client'
import { uniqueNames, zipStore, type ZipEntry } from '../../lib/zip'
import { Button } from '../ui/Button'

export interface ZipFile { projectId: number; fileName: string }

interface Props {
  gameName: string
  files: ZipFile[]
  ratio: number
  /** 시트를 하나 골랐으면 그 이름 — "이 시트만"을 고를 수 있다. 없으면 그 칸은 꺼진다. */
  sheetName: string | null
  /** "이 시트만"을 고르면 창을 닫고 기존 시트 내보내기로 넘어간다. */
  onSheet: () => void
  /** 다 묶은 zip을 내려받게 한다. 내보낸 파일들의 N차 목록도 새로 읽게 projectId를 넘긴다. */
  onDone: (zip: Blob, name: string, projectIds: number[]) => void
  onClose: () => void
}

type Phase = { kind: 'pick' } | { kind: 'ready' } | { kind: 'running'; index: number } | { kind: 'done'; ok: number; failed: { fileName: string; message: string }[]; cancelled: boolean }

/**
 * 게임 화면 내보내기(2026-10-28): "이 시트 내보내기"를 누르면 먼저 이 시트만 / 전체 파일 zip을 고른다.
 * 전체 파일 zip은 게임사에 줄 납품본이라 파일마다 기존 한국어 열에 덮어쓰고 플래그·비고 열과 이력·단어장 시트는 넣지 않는다.
 * 파일 하나씩 차례로 서버에서 만들어 받으므로 진행률은 파일 단위다. 실패한 파일은 건너뛰고 끝에 알린다.
 */
export function GameZipExportDialog({ gameName, files, ratio, sheetName, onSheet, onDone, onClose }: Props) {
  const [phase, setPhase] = useState<Phase>({ kind: 'pick' })
  const cancel = useRef(false)
  const running = phase.kind === 'running'
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !running) { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose, running])

  const start = async () => {
    cancel.current = false
    const got: { name: string; data: Uint8Array; projectId: number }[] = []
    const failed: { fileName: string; message: string }[] = []
    let i = 0
    for (; i < files.length && !cancel.current; i++) {
      setPhase({ kind: 'running', index: i })
      const f = files[i]
      try {
        const r = await api.exportFile(f.projectId, { addFlagColumn: false, addHistorySheet: false, addGlossarySheet: false, ratio, overwriteKo: true, addNoteColumn: false })
        got.push({ name: r.name ?? f.fileName, data: new Uint8Array(await r.blob.arrayBuffer()), projectId: f.projectId })
      } catch (e) {
        failed.push({ fileName: f.fileName, message: e instanceof Error ? e.message : '내보내기에 실패했습니다.' })
      }
    }
    const cancelled = i < files.length
    if (got.length > 0) {
      const names = uniqueNames(got.map((g) => g.name))
      const entries: ZipEntry[] = got.map((g, k) => ({ name: names[k], data: g.data }))
      onDone(zipStore(entries), `${gameName || '게임'}_${stamp(new Date())}.zip`, got.map((g) => g.projectId))
    }
    setPhase({ kind: 'done', ok: got.length, failed, cancelled })
  }

  const total = files.length
  const done = phase.kind === 'running' ? phase.index : phase.kind === 'done' ? total : 0
  return (
    <div className="fade-in fixed inset-0 z-50 flex items-center justify-center bg-ink/30" role="dialog" aria-modal="true" aria-label="내보내기"
      onMouseDown={(e) => { if (e.target === e.currentTarget && !running) onClose() }}>
      <div className="pop-in w-[28rem] rounded-2xl bg-white p-5 shadow-2xl">
        <h2 className="text-[15px] font-semibold text-ink">{phase.kind === 'pick' ? '무엇을 내보낼까요?' : '전체 파일 zip 내보내기'}</h2>
        {phase.kind === 'pick' && (
          <>
            <div className="mt-3 grid gap-2">
              <button type="button" disabled={sheetName === null} onClick={onSheet}
                className="rounded-xl border border-line px-3 py-2.5 text-left enabled:hover:border-primary enabled:hover:bg-primarySoft focus-visible:border-primary focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50">
                <span className="block text-[13px] font-medium text-ink">이 시트만</span>
                <span className="block truncate text-xs text-muted">{sheetName ?? '시트를 하나만 고르면 그 시트만 내보낼 수 있습니다'}</span>
              </button>
              <button type="button" autoFocus disabled={total === 0} onClick={() => setPhase({ kind: 'ready' })}
                className="rounded-xl border border-line px-3 py-2.5 text-left enabled:hover:border-primary enabled:hover:bg-primarySoft focus-visible:border-primary focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50">
                <span className="block text-[13px] font-medium text-ink">전체 파일 zip</span>
                <span className="block text-xs text-muted">파일 {total}개를 원본 엑셀 모양으로 · 진행률은 파일 단위</span>
              </button>
            </div>
            <div className="mt-4 flex justify-end">
              <Button onClick={onClose}>취소</Button>
            </div>
          </>
        )}
        {phase.kind === 'ready' && (
          <>
            <p className="mt-2 break-keep text-xs text-body">
              파일 {total}개를 원본 모양 그대로 만들어 zip 하나로 묶습니다. Fix는 기존 한국어 열에 덮어쓰고 플래그·비고 열과 이력·단어장 시트는 넣지 않습니다.
              파일마다 내보내기 차수가 하나씩 올라갑니다.
            </p>
            <ul className="mt-3 max-h-40 overflow-auto rounded-lg border border-line px-3 py-2 text-xs text-body">
              {files.map((f) => <li key={f.projectId} className="truncate" title={f.fileName}>{f.fileName}</li>)}
            </ul>
            <div className="mt-4 flex justify-end gap-2">
              <Button onClick={onClose}>취소</Button>
              <Button variant="primary" autoFocus disabled={total === 0} onClick={() => void start()}>zip 만들기</Button>
            </div>
          </>
        )}
        {phase.kind === 'running' && (
          <>
            <div className="mt-3 flex items-baseline justify-between text-xs">
              <span className="min-w-0 truncate text-body" title={files[phase.index].fileName}>{files[phase.index].fileName}</span>
              <span className="ml-3 shrink-0 tabular-nums text-muted">{phase.index + 1} / {total} 파일</span>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-canvas" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done}
              aria-label="내보낸 파일 수">
              <div className="h-full rounded-full bg-primary transition-[width] duration-300" style={{ width: `${(done / Math.max(1, total)) * 100}%` }} />
            </div>
            <div className="mt-4 flex justify-end">
              <Button onClick={() => { cancel.current = true }}>지금 파일까지만</Button>
            </div>
          </>
        )}
        {phase.kind === 'done' && (
          <>
            <p className="mt-2 text-[13px] text-ink">
              {phase.ok > 0 ? `파일 ${phase.ok}개를 zip으로 받았습니다.` : '받은 파일이 없습니다.'}
              {phase.cancelled && ` 나머지 ${total - phase.ok - phase.failed.length}개는 멈춰서 넣지 않았습니다.`}
            </p>
            {phase.failed.length > 0 && (
              <div className="mt-3 rounded-lg border border-line px-3 py-2 text-xs">
                <p className="font-medium text-ink">못 넣은 파일 {phase.failed.length}개</p>
                <ul className="mt-1 max-h-40 overflow-auto text-body">
                  {phase.failed.map((f) => <li key={f.fileName} className="break-keep">{f.fileName} — {f.message}</li>)}
                </ul>
              </div>
            )}
            <div className="mt-4 flex justify-end">
              <Button variant="primary" autoFocus onClick={onClose}>닫기</Button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

const pad = (n: number) => String(n).padStart(2, '0')
const stamp = (d: Date) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}`
