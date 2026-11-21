import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type DragEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { Project } from '../api/types'
import { FileRowMenu } from '../components/FileRowMenu'
import { QuickTranslate } from '../components/QuickTranslate'
import { AppHeader, Progress } from '../components/ui/AppHeader'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Chip } from '../components/ui/Chip'
import { inputClass } from '../components/ui/Field'
import { ACCEPT, MAX_BYTES, filesFromDataTransfer, splitUploadable } from '../lib/files'

/** 한 번의 업로드(파일 여러 개나 폴더) 결과. */
interface UploadReport { done: Project[]; failed: { name: string; message: string }[]; skipped: number; total: number }

type Stage = 'SETUP' | 'REVIEW' | 'DONE'
const STAGE_LABEL: Record<Stage, string> = { SETUP: '정규화 전', REVIEW: '검수 중', DONE: '확정 끝' }
const STAGE_STYLE: Record<Stage, string> = { SETUP: 'bg-orangeSoft text-[#a3470f]', REVIEW: 'bg-primarySoft text-primary', DONE: 'bg-greenSoft text-green' }
type StageFilter = 'ALL' | Stage

// 정규화를 한 번이라도 했으면 currentStep이 3 이상이다(reviewOnly 값과 무관).
const normalized = (p: Project) => p.currentStep >= 3
const stageOf = (p: Project): Stage => {
  if (!normalized(p)) return 'SETUP'
  const rows = p.reviewRows ?? 0
  return rows > 0 && (p.confirmedRows ?? 0) >= rows ? 'DONE' : 'REVIEW'
}
const resumePath = (p: Project) => (normalized(p) ? `/review/${p.id}` : `/normalize/${p.id}`)
const iconButton = 'press flex h-9 w-9 items-center justify-center rounded-lg text-body hover:bg-canvas hover:text-ink'

export function GamePage() {
  const gameId = Number(useParams<{ gameId: string }>().gameId)
  const navigate = useNavigate()
  const qc = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const folderRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [report, setReport] = useState<UploadReport | null>(null)
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [renaming, setRenaming] = useState(false)
  const [nameDraft, setNameDraft] = useState('')
  const [confirmGameDelete, setConfirmGameDelete] = useState(false)
  // 파일 삭제는 메뉴에서 고른 뒤 줄 안에서 한 번 더 눌러 확정한다(브라우저 confirm 대신 인라인 확인).
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null)
  const [stage, setStage] = useState<StageFilter>('ALL')
  const termFileRef = useRef<HTMLInputElement>(null)
  const [termError, setTermError] = useState<string | null>(null)

  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId) })
  const list = useQuery({ queryKey: ['projects', gameId], queryFn: () => api.projects.list(gameId), refetchOnWindowFocus: true })
  const termRequests = useQuery({ queryKey: ['termRequests', gameId], queryFn: () => api.termRequests.list(gameId) })
  const invalidate = () => { qc.invalidateQueries({ queryKey: ['projects'] }); qc.invalidateQueries({ queryKey: ['games'] }); qc.invalidateQueries({ queryKey: ['game', gameId] }) }

  // 폴더 선택 input은 React 속성으로 못 넣어서 직접 붙인다
  useEffect(() => { folderRef.current?.setAttribute('webkitdirectory', ''); folderRef.current?.setAttribute('directory', '') }, [])

  const rename = useMutation({
    mutationFn: (name: string) => api.games.rename(gameId, name),
    onSuccess: (g) => { qc.setQueryData(['game', gameId], g); setRenaming(false); setError(null); qc.invalidateQueries({ queryKey: ['games'] }) },
    onError: (e: Error) => setError(e.message),
  })
  const uploadTermRequest = useMutation({
    mutationFn: (file: File) => api.termRequests.upload(gameId, file),
    onSuccess: (r) => { setTermError(null); qc.invalidateQueries({ queryKey: ['termRequests', gameId] }); navigate(`/games/${gameId}/glossary/requests/${r.id}`) },
    onError: (e: Error) => setTermError(e.message),
  })
  const removeGame = useMutation({
    mutationFn: () => api.games.delete(gameId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['games'] }); navigate('/') },
    onError: (e: Error) => { setConfirmGameDelete(false); setError(e.message) },
  })
  const removeProject = useMutation({
    mutationFn: (id: number) => api.projects.delete(id),
    onSuccess: (_v, id) => {
      setConfirmDeleteId(null)
      setError(null)
      setReport((r) => r && { ...r, done: r.done.filter((p) => p.id !== id) })
      invalidate()
    },
    onError: (e: Error) => { setConfirmDeleteId(null); setError(e.message) },
  })

  /** 파일을 하나씩 순서대로 올린다. 하나가 실패해도 나머지는 계속. */
  const uploadAll = async (files: File[]) => {
    const { accepted, skipped } = splitUploadable(files)
    const next: UploadReport = { done: [], failed: [], skipped, total: accepted.length }
    setError(null)
    setReport(null)
    setProgress({ done: 0, total: accepted.length })
    for (const f of accepted) {
      if (f.size > MAX_BYTES) next.failed.push({ name: f.name, message: '최대 50 MB까지 올릴 수 있습니다.' })
      else {
        try { next.done.push(await api.projects.upload(f, gameId)) }
        catch (e) { next.failed.push({ name: f.name, message: (e as Error).message }) }
      }
      setProgress({ done: next.done.length + next.failed.length, total: accepted.length })
    }
    setProgress(null)
    setReport(next)
    invalidate()
  }
  // 화면 어디에 놓아도 받는다. 안쪽 요소로 옮겨 가며 생기는 dragleave는 무시한다.
  const onDragOver = (e: DragEvent) => { if (!e.dataTransfer.types.includes('Files')) return; e.preventDefault(); setDragging(true) }
  const onDragLeave = (e: DragEvent) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false) }
  const onDrop = async (e: DragEvent) => {
    e.preventDefault()
    setDragging(false)
    uploadAll(await filesFromDataTransfer(e.dataTransfer))
  }
  const onPick = (input: HTMLInputElement) => { const files = Array.from(input.files ?? []); input.value = ''; if (files.length) uploadAll(files) }
  const single = report && report.total === 1 && report.done.length === 1 ? report.done[0] : null
  const busy = progress !== null

  const files = list.data ?? []
  const counts: Record<StageFilter, number> = { ALL: files.length, SETUP: 0, REVIEW: 0, DONE: 0 }
  for (const p of files) counts[stageOf(p)]++
  const shown = stage === 'ALL' ? files : files.filter((p) => stageOf(p) === stage)
  const totalRows = files.reduce((n, p) => n + p.rowCount, 0)
  const g = game.data
  const pct = g && (g.reviewRows ?? 0) > 0 ? Math.round(((g.confirmedRows ?? 0) / g.reviewRows!) * 100) : null

  return (
    <div className="relative flex min-h-screen flex-col" onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop}>
      <AppHeader crumbs={[{ label: g?.name ?? '…' }]} />

      {dragging && (
        <div aria-hidden className="fade-in pointer-events-none fixed inset-0 z-40 flex items-center justify-center bg-primary/10 p-6">
          <div className="flex h-full w-full items-center justify-center rounded-2xl border-2 border-dashed border-primary bg-white/80 text-lg font-semibold text-primary backdrop-blur-sm">
            놓으면 이 게임에 올립니다 — 파일·폴더 모두 됩니다
          </div>
        </div>
      )}

      <main className="mx-auto flex w-full max-w-[1080px] flex-col gap-5 px-6 pb-16 pt-8">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            {renaming
              ? <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); if (nameDraft.trim()) rename.mutate(nameDraft.trim()) }}>
                  <input aria-label="게임 이름" autoFocus className={`${inputClass} w-72 text-lg font-semibold`} value={nameDraft} onChange={(e) => setNameDraft(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Escape') setRenaming(false) }} />
                  <Button type="submit" variant="primary" disabled={!nameDraft.trim() || rename.isPending}>저장</Button>
                  <Button onClick={() => setRenaming(false)}>취소</Button>
                </form>
              : <div className="group flex items-center gap-1.5">
                  <h1 className="text-[28px] font-bold leading-tight tracking-[-0.02em]">{g?.name ?? '…'}</h1>
                  {g && (
                    <button type="button" aria-label="이름 바꾸기" title="이름 바꾸기" onClick={() => { setNameDraft(g.name); setRenaming(true) }}
                      className="press flex h-8 w-8 items-center justify-center rounded-md text-muted opacity-60 hover:bg-white hover:text-ink group-hover:opacity-100 focus-visible:opacity-100">
                      <svg aria-hidden width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 20h4L19 9l-4-4L4 16v4z" /></svg>
                    </button>
                  )}
                </div>}
            {g && (
              <dl className="flex flex-wrap gap-x-5 gap-y-1 text-[13px] text-body">
                <div className="flex gap-1"><dt className="sr-only">파일</dt><dd><b className="font-semibold tabular-nums text-ink">{g.projects.toLocaleString()}</b> 파일</dd></div>
                <div className="flex gap-1"><dt className="sr-only">행</dt><dd><b className="font-semibold tabular-nums text-ink">{totalRows.toLocaleString()}</b> 행</dd></div>
                {pct !== null && <div className="flex gap-1"><dt className="sr-only">확정</dt><dd><b className="font-semibold tabular-nums text-green">{pct}%</b> 확정</dd></div>}
                <div className="flex gap-1"><dt className="sr-only">번역 메모리</dt><dd>메모리 <b className="font-semibold tabular-nums text-ink">{g.memory.toLocaleString()}</b>행 · 파일끼리 공유</dd></div>
              </dl>
            )}
          </div>
          <div className="flex items-center gap-2">
            <input ref={termFileRef} aria-label="단어장 요청 양식 선택" type="file" accept=".xlsx" className="sr-only"
              onChange={(e) => { const f = e.currentTarget.files?.[0]; e.currentTarget.value = ''; if (f) uploadTermRequest.mutate(f) }} />
            <Button disabled={uploadTermRequest.isPending} onClick={() => termFileRef.current?.click()}>
              {uploadTermRequest.isPending ? '올리는 중…' : '단어장 요청 올리기'}
            </Button>
            {g && g.projects > 0 && (
              // 게임의 모든 파일·시트를 한 표로(설계 2026-10-12)
              <Link to={`/games/${gameId}/review`} className="press inline-flex items-center rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-[#1a4084]">
                게임 전체 리뷰
              </Link>
            )}
          </div>
        </div>
        {termError && <p className="-mt-3 text-xs text-orange">{termError}</p>}

        <Card className="overflow-visible">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-lineSoft px-5 py-3">
            <div className="flex items-center gap-3">
              <h2 className="text-base font-semibold">파일</h2>
              {files.length > 0 && (
                <div role="group" aria-label="단계로 거르기" className="flex gap-1">
                  {(['ALL', 'REVIEW', 'SETUP', 'DONE'] as StageFilter[]).filter((s) => s === 'ALL' || counts[s] > 0).map((s) => (
                    <button key={s} type="button" aria-pressed={stage === s} onClick={() => setStage(s)}
                      className={`press h-7 rounded-full px-2.5 text-xs ${stage === s ? 'bg-ink text-white' : 'border border-line bg-white text-body hover:bg-canvas'}`}>
                      {s === 'ALL' ? '전체' : STAGE_LABEL[s]} <span className="tabular-nums opacity-75">{counts[s]}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="flex items-center gap-2">
              <span className="hidden text-xs text-body sm:inline">화면 어디에나 끌어다 놓아도 됩니다</span>
              <input ref={fileRef} aria-label="파일 선택" type="file" accept={ACCEPT} multiple className="sr-only" onChange={(e) => onPick(e.currentTarget)} />
              <input ref={folderRef} aria-label="폴더 선택" type="file" multiple className="sr-only" onChange={(e) => onPick(e.currentTarget)} />
              <Button onClick={() => folderRef.current?.click()} disabled={busy}>폴더</Button>
              <Button onClick={() => fileRef.current?.click()} disabled={busy} className="!border-primary !text-primary">
                {busy ? `${progress.done}/${progress.total} 올리는 중…` : '+ 파일 올리기'}
              </Button>
            </div>
          </div>

          {(report || error) && (
            <div role="status" className="flex flex-wrap items-center justify-between gap-2 border-b border-lineSoft bg-canvas px-5 py-2.5 text-xs">
              <div className="flex flex-col gap-0.5">
                {error && <p className="text-orange">{error}</p>}
                {report && (single
                  ? <p className="text-green">올림: {single.fileName} · 시트 {single.sheetCount} · 행 {single.rowCount.toLocaleString()}</p>
                  : <p className={report.failed.length > 0 ? 'text-orange' : 'text-green'}>올림 {report.done.length}개 · 실패 {report.failed.length}개 · 건너뜀 {report.skipped}개</p>)}
                {report?.failed.map((f) => <p key={f.name} className="text-orange">{f.name}: {f.message}</p>)}
                {(report?.skipped ?? 0) > 0 && <p className="text-body">건너뜀 = 지원하지 않는 확장자, 엑셀 잠금 파일(~$), 숨김 파일</p>}
              </div>
              <div className="flex items-center gap-2">
                {/* 파일을 하나 올렸을 때만 바로 다음 단계로. 여러 개면 표에서 각각 진행한다. */}
                {single && <Button variant="primary" onClick={() => navigate(`/normalize/${single.id}`)}>정규화로</Button>}
                <button type="button" aria-label="알림 닫기" onClick={() => { setReport(null); setError(null) }} className="px-1 text-body hover:text-ink">✕</button>
              </div>
            </div>
          )}

          {files.length === 0 && list.isFetched
            ? (
              <div className="flex flex-col items-center gap-2 px-5 py-14 text-center">
                <p className="text-[15px] font-semibold">아직 올린 파일이 없습니다</p>
                <p className="text-xs text-body">xlsx · xlsm · xls · csv · tsv · 파일당 최대 50 MB — 여기에 끌어다 놓거나 위의 「+ 파일 올리기」를 누르세요.</p>
              </div>
            )
            : (
              <table className="w-full table-fixed text-[13px]">
                <colgroup><col /><col className="w-28" /><col className="w-56" /><col className="w-28" /><col className="w-24" /></colgroup>
                <thead className="bg-tableHead text-left text-xs text-body">
                  <tr>
                    <th className="px-5 py-2.5 font-medium">파일 이름</th><th className="px-3 py-2.5 font-medium">단계</th>
                    <th className="px-3 py-2.5 font-medium">확정 진행</th><th className="px-3 py-2.5 text-right font-medium">올린 날</th><th className="px-3 py-2.5"><span className="sr-only">동작</span></th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((p) => {
                    const st = stageOf(p)
                    return (
                      <tr key={p.id} className="border-t border-lineSoft hover:bg-canvas/60">
                        <td className="px-5 py-2.5">
                          <Link to={resumePath(p)} className="group flex min-w-0 flex-col gap-0.5 text-ink">
                            <span className="flex min-w-0 items-center gap-2">
                              <span className="truncate text-sm font-semibold group-hover:text-primary">{p.fileName}</span>
                              {p.finalReview && <Chip color="green">최종 리뷰</Chip>}
                            </span>
                            <span className="text-xs text-body">시트 {p.sheetCount} · {p.rowCount.toLocaleString()}행</span>
                          </Link>
                        </td>
                        <td className="px-3 py-2.5"><span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${STAGE_STYLE[st]}`}>{STAGE_LABEL[st]}</span></td>
                        <td className="px-3 py-2.5">
                          {st === 'SETUP' ? <span className="text-xs text-body">—</span> : <Progress done={p.confirmedRows ?? 0} total={p.reviewRows ?? 0} showCount />}
                        </td>
                        <td className="px-3 py-2.5 text-right tabular-nums text-body">{p.uploadedAt.slice(0, 10)}</td>
                        <td className="px-3 py-2">
                          {confirmDeleteId === p.id
                            ? (
                              <div className="flex justify-end gap-1.5">
                                <Button variant="danger" onClick={() => removeProject.mutate(p.id)} disabled={removeProject.isPending} className="!px-2.5 !py-1.5 !text-xs">정말 삭제</Button>
                                <Button onClick={() => setConfirmDeleteId(null)} disabled={removeProject.isPending} className="!px-2.5 !py-1.5 !text-xs">취소</Button>
                              </div>
                            )
                            : (
                              <div className="flex justify-end gap-0.5">
                                <a href={api.urls.original(p.id)} download aria-label={`${p.fileName} 원본 내려받기`} title="원본 내려받기" className={iconButton}>
                                  <svg aria-hidden width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12 4v11M7 10l5 5 5-5M5 20h14" /></svg>
                                </a>
                                <FileRowMenu label={`${p.fileName} 더 보기`} items={[
                                  ...(normalized(p) ? [{ label: '시트·열 설정', onSelect: () => navigate(`/normalize/${p.id}`) }] : []),
                                  { label: '삭제…', danger: true, onSelect: () => setConfirmDeleteId(p.id) },
                                ]} />
                              </div>
                            )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
        </Card>

        {termRequests.data && termRequests.data.length > 0 && (
          <Card>
            <h2 className="border-b border-lineSoft px-5 py-3 text-base font-semibold">단어장 요청</h2>
            <ul className="flex flex-col">
              {termRequests.data.map((r) => (
                <li key={r.id} className="border-t border-lineSoft first:border-t-0">
                  <Link to={`/games/${gameId}/glossary/requests/${r.id}`} className="flex items-center justify-between gap-4 px-5 py-2.5 text-[13px] text-ink hover:bg-canvas">
                    <span>{r.fileName} · {new Date(r.createdAt).toLocaleDateString('ko-KR')} · 승인 {r.approved}/{r.total}</span>
                    <span className="w-40"><Progress done={r.approved} total={r.total} /></span>
                  </Link>
                </li>
              ))}
            </ul>
          </Card>
        )}

        <QuickTranslate gameId={gameId} collapsible />

        <div className="flex items-center gap-2 pt-4 text-xs">
          {confirmGameDelete
            ? <>
                <span className="text-orange">이 게임과 파일 {g?.projects ?? 0}개와 번역 메모리가 모두 지워집니다.</span>
                <Button variant="danger" onClick={() => removeGame.mutate()} disabled={removeGame.isPending}>정말 삭제</Button>
                <Button onClick={() => setConfirmGameDelete(false)} disabled={removeGame.isPending}>취소</Button>
              </>
            : <button type="button" onClick={() => setConfirmGameDelete(true)} className="text-body underline-offset-2 hover:text-[#c0392b] hover:underline">게임 삭제</button>}
        </div>
      </main>
    </div>
  )
}
