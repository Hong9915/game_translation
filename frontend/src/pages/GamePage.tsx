import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type DragEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { Project } from '../api/types'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { inputClass } from '../components/ui/Field'
import { ProjectFiles } from '../components/ProjectFiles'
import { QuickTranslate } from '../components/QuickTranslate'
import { ACCEPT, MAX_BYTES, filesFromDataTransfer, splitUploadable } from '../lib/files'

/** 한 번의 업로드(파일 여러 개나 폴더) 결과. */
interface UploadReport { done: Project[]; failed: { name: string; message: string }[]; skipped: number; total: number }

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
  // 파일 삭제는 두 번 눌러 확정한다(브라우저 confirm 대신 인라인 확인).
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)

  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId) })
  const list = useQuery({ queryKey: ['projects', gameId], queryFn: () => api.projects.list(gameId) })
  const invalidate = () => { qc.invalidateQueries({ queryKey: ['projects'] }); qc.invalidateQueries({ queryKey: ['games'] }); qc.invalidateQueries({ queryKey: ['game', gameId] }) }

  // 폴더 선택 input은 React 속성으로 못 넣어서 직접 붙인다
  useEffect(() => { folderRef.current?.setAttribute('webkitdirectory', ''); folderRef.current?.setAttribute('directory', '') }, [])

  const rename = useMutation({
    mutationFn: (name: string) => api.games.rename(gameId, name),
    onSuccess: (g) => { qc.setQueryData(['game', gameId], g); setRenaming(false); setError(null); qc.invalidateQueries({ queryKey: ['games'] }) },
    onError: (e: Error) => setError(e.message),
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
  const onDrop = async (e: DragEvent) => {
    e.preventDefault()
    setDragging(false)
    uploadAll(await filesFromDataTransfer(e.dataTransfer))
  }
  const onPick = (input: HTMLInputElement) => { const files = Array.from(input.files ?? []); input.value = ''; if (files.length) uploadAll(files) }
  // 정규화를 한 번이라도 했으면 currentStep이 3 이상이다(reviewOnly 값과 무관).
  const normalized = (p: Project) => p.currentStep >= 3
  const resumePath = (p: Project) => (normalized(p) ? `/review/${p.id}` : `/normalize/${p.id}`)
  const stepLabel = (p: Project) => (normalized(p) ? '검수 중' : '정규화 전')
  const single = report && report.total === 1 && report.done.length === 1 ? report.done[0] : null
  const busy = progress !== null

  return (
    <div className="mx-auto flex min-h-screen max-w-[1100px] flex-col gap-4 px-10 pt-9">
      <div className="flex items-end justify-between gap-4">
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center gap-3">
            <Link to="/" aria-label="게임 목록으로" title="게임 목록으로"
              className="press inline-flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-lg border border-line bg-white text-base text-body hover:bg-canvas hover:text-ink">←</Link>
          {renaming
            ? <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); if (nameDraft.trim()) rename.mutate(nameDraft.trim()) }}>
                <input aria-label="게임 이름" autoFocus className={`${inputClass} w-72 text-lg font-semibold`} value={nameDraft} onChange={(e) => setNameDraft(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Escape') setRenaming(false) }} />
                <Button type="submit" variant="primary" disabled={!nameDraft.trim() || rename.isPending}>저장</Button>
                <Button onClick={() => setRenaming(false)}>취소</Button>
              </form>
            : <div className="flex items-center gap-3">
                <h1 className="text-[22px] font-semibold leading-tight">{game.data?.name ?? '…'}</h1>
                {game.data && <button type="button" className="text-xs text-muted hover:underline" onClick={() => { setNameDraft(game.data.name); setRenaming(true) }}>이름 바꾸기</button>}
              </div>}
          </div>
          <p className="max-w-[640px] text-sm text-body">
            {game.data ? `파일 ${game.data.projects.toLocaleString()}개 · ` : ''}
            이 게임의 파일끼리 번역 메모리를 공유합니다.
          </p>
        </div>
      </div>

      <Card className="p-5">
        <div
          onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          className={`flex flex-col items-center justify-center gap-3 rounded-lg border-2 border-dashed px-6 py-12 ${dragging ? 'border-primary bg-primarySoft' : 'border-line'}`}
        >
          <p className="text-base font-semibold">xlsx · CSV 파일이나 폴더를 끌어다 놓거나 아래에서 선택</p>
          <p className="text-xs text-muted">.xlsx · .xlsm · .xls · .csv · .tsv · 파일당 최대 50 MB · 폴더는 하위 폴더까지 훑고 파일마다 따로 진행합니다</p>
          <input ref={fileRef} aria-label="파일 선택" type="file" accept={ACCEPT} multiple className="sr-only" onChange={(e) => onPick(e.currentTarget)} />
          <input ref={folderRef} aria-label="폴더 선택" type="file" multiple className="sr-only" onChange={(e) => onPick(e.currentTarget)} />
          <div className="flex gap-2">
            <Button variant="primary" onClick={() => fileRef.current?.click()} disabled={busy}>{busy ? `${progress.done}/${progress.total} 올리는 중…` : '파일 선택'}</Button>
            <Button onClick={() => folderRef.current?.click()} disabled={busy}>폴더 선택</Button>
          </div>
          {error && <p className="text-xs text-orange">{error}</p>}
          {report && (
            <div className="flex flex-col items-center gap-1 text-xs">
              {single
                ? <p className="text-green">올림: {single.fileName} · 시트 {single.sheetCount} · 행 {single.rowCount.toLocaleString()}</p>
                : <p className={report.failed.length > 0 ? 'text-orange' : 'text-green'}>올림 {report.done.length}개 · 실패 {report.failed.length}개 · 건너뜀 {report.skipped}개</p>}
              {report.failed.map((f) => <p key={f.name} className="text-orange">{f.name}: {f.message}</p>)}
              {report.skipped > 0 && <p className="text-muted">건너뜀 = 지원하지 않는 확장자, 엑셀 잠금 파일(~$), 숨김 파일</p>}
            </div>
          )}
        </div>
      </Card>

      <QuickTranslate gameId={gameId} />

      <Card>
        <div className="flex items-center justify-between px-5 py-3">
          <span className="text-base font-semibold">이 게임의 파일</span>
          <span className="text-xs text-muted">파일 이름을 누르면 원본을 내려받을 수 있습니다</span>
        </div>
        <table className="w-full text-[13px]">
          <thead className="bg-tableHead text-left text-xs text-muted">
            <tr><th className="px-3 py-2 font-medium">원본 파일</th><th className="px-3 py-2 font-medium">시트</th><th className="px-3 py-2 font-medium">행</th><th className="px-3 py-2 font-medium">단계</th><th className="px-3 py-2 font-medium">올린 날짜</th><th className="px-3 py-2" /></tr>
          </thead>
          <tbody>
            {(list.data ?? []).flatMap((p) => {
              const row = (
                <tr key={p.id} className="border-t border-lineSoft">
                  <td className="px-3 py-3">
                    <button type="button" className="text-left font-medium hover:underline" aria-expanded={openId === p.id}
                      onClick={() => setOpenId(openId === p.id ? null : p.id)}><span aria-hidden className="mr-1 text-muted">{openId === p.id ? '▾' : '▸'}</span><span>{p.fileName}</span></button>
                  </td>
                  <td className="px-3 py-3">{p.sheetCount}</td>
                  <td className="px-3 py-3">{p.rowCount.toLocaleString()}</td>
                  <td className="px-3 py-3 text-muted">{stepLabel(p)}</td>
                  <td className="px-3 py-3">{p.uploadedAt.slice(0, 10)}</td>
                  <td className="px-3 py-2 text-right">
                    <div className="flex justify-end gap-2">
                      <Button onClick={() => navigate(resumePath(p))}>이어서 진행</Button>
                      {confirmDeleteId === p.id
                        ? <>
                            <Button variant="danger" onClick={() => removeProject.mutate(p.id)} disabled={removeProject.isPending}>정말 삭제</Button>
                            <Button onClick={() => setConfirmDeleteId(null)} disabled={removeProject.isPending}>취소</Button>
                          </>
                        : <Button onClick={() => setConfirmDeleteId(p.id)} disabled={removeProject.isPending} aria-label={`${p.fileName} 삭제`}>삭제</Button>}
                    </div>
                  </td>
                </tr>
              )
              return openId === p.id
                ? [row, <tr key={`${p.id}-files`} className="bg-canvas"><td colSpan={6} className="px-6 py-3"><ProjectFiles project={p} /></td></tr>]
                : [row]
            })}
            {list.data?.length === 0 && <tr><td colSpan={6} className="px-3 py-6 text-center text-muted">아직 올린 파일이 없습니다.</td></tr>}
          </tbody>
        </table>
      </Card>

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-xs">
          {confirmGameDelete
            ? <>
                <span className="text-orange">이 게임과 파일 {game.data?.projects ?? 0}개와 용어집·번역 메모리가 모두 지워집니다.</span>
                <Button variant="danger" onClick={() => removeGame.mutate()} disabled={removeGame.isPending}>정말 삭제</Button>
                <Button onClick={() => setConfirmGameDelete(false)} disabled={removeGame.isPending}>취소</Button>
              </>
            : <Button onClick={() => setConfirmGameDelete(true)}>게임 삭제</Button>}
        </div>
        {/* 파일을 하나 올렸을 때만 바로 다음 단계로. 여러 개면 표에서 각각 진행한다. */}
        {(report === null || single) && <Button variant="primary" disabled={!single} onClick={() => single && navigate(`/normalize/${single.id}`)}>정규화로</Button>}
      </div>
    </div>
  )
}
