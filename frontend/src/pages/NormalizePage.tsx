import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { useProject, useProjectId } from '../api/hooks'
import type { ColumnPair, NormalizeRequest, SheetOption } from '../api/types'
import { ColumnRoleTable } from '../components/normalize/ColumnRoleTable'
import { SheetChips } from '../components/normalize/SheetChips'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { inputClass } from '../components/ui/Field'
import { applyFixMode, applyRole, canOverwrite, defaultFixHeader, type FixMode, type RoleConfig } from '../lib/columnRoles'

/** 시트 추측값으로 첫 설정을 만든다. 한국어 열을 찾았으면 기존 열을 읽고 Fix는 새 열에 쓴다. */
function configFromGuess(s: SheetOption): RoleConfig {
  const guessed = s.guess.pairs.length > 0 ? s.guess.pairs : [{ src: s.guess.src ?? '', en: s.guess.en, ko: null }]
  const pairs: ColumnPair[] = guessed.map((g, i) => ({
    srcCol: g.src, enCol: g.en,
    koColMode: g.ko ? 'EXISTING_TO_NEW' : 'NEW',
    koCol: g.ko, koHeader: defaultFixHeader(i),
  }))
  return { keyCol: s.guess.key, pairs }
}

export function NormalizePage() {
  const id = useProjectId() as number
  const project = useProject(id).data
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [headerRow, setHeaderRow] = useState(1)
  const [sheet, setSheet] = useState<string | null>(null)
  const [config, setConfig] = useState<RoleConfig | null>(null)
  const [fix, setFix] = useState<FixMode>('NEW')
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null)
  // 이미 행이 있는 시트를 다시 정규화하려는 걸 확인한다 — 확인 전엔 PUT을 보내지 않는다.
  const [confirmRerun, setConfirmRerun] = useState(false)

  const sheets = useQuery({ queryKey: ['sheets', id, headerRow], queryFn: () => api.sheets(id, headerRow) })
  const current = useQuery({ queryKey: ['normalize-current', id], queryFn: () => api.normalizeCurrent(id) })

  const options = sheets.data?.sheets ?? []
  const active = options.find((s) => s.name === sheet) ?? options[0]
  // 시트를 처음 보거나 바꿨으면 추측값(또는 저장된 설정)으로 설정을 채운다(렌더 단계 보정 — 한 번만 참이다).
  // current(저장된 설정)가 정착하기 전에는 채우지 않는다 — sheets가 먼저 응답하면 이 보정이 먼저 실행돼
  // 추측값으로 채워지고, 뒤늦게 온 저장된 설정은(조건이 이미 거짓이라) 다시 반영되지 않는 경합이 있었다.
  // current.isFetched는 성공·실패 어느 쪽으로 끝나도 참이 된다(조회가 실패해도 화면이 안 멈추게).
  if (active && (sheet === null || config === null) && current.isFetched) {
    setSheet(active.name)
    const saved = current.data?.configs.find((c) => c.config.sheetName === active.name)?.config
    setConfig(saved ? { keyCol: saved.keyCol, pairs: saved.pairs } : configFromGuess(active))
    setFix(saved?.pairs.some((p) => p.koColMode === 'EXISTING') ? 'OVERWRITE' : 'NEW')
  }
  // 덮어쓸 기존 한국어 열이 사라지면 모드를 되돌린다 — 렌더 단계에서 한 번만 참인 보정
  if (config && fix === 'OVERWRITE' && !canOverwrite(config)) {
    setFix('NEW')
    setConfig(applyFixMode(config, 'NEW'))
  }

  const pickFix = (m: FixMode) => { setFix(m); setConfig((c) => applyFixMode(c!, m)) }

  const configuredNames = new Set((current.data?.configs ?? []).map((c) => c.config.sheetName))
  const chips = (current.data?.configs ?? []).map((c) => ({ name: c.config.sheetName, rows: c.stats.normalized }))
  const addOptions = options.filter((s) => !configuredNames.has(s.name)).map((s) => ({ name: s.name, rowCount: s.rowCount }))
  const activeStats = current.data?.configs.find((c) => c.config.sheetName === sheet)?.stats
  // 이 시트에 이미 정규화된 행이 있으면 다시 정규화할 때 번역·이력·담당·완료가 지워진다 — 확인을 받아야 한다.
  const alreadyNormalized = (activeStats?.totalRows ?? 0) > 0

  const removeMutation = useMutation({
    mutationFn: (sheetName: string) => api.removeSheet(id, sheetName),
    onSuccess: (_v, sheetName) => {
      setConfirmRemove(null)
      qc.invalidateQueries({ queryKey: ['normalize-current', id] })
      if (sheet === sheetName) { setSheet(null); setConfig(null) }
    },
    onError: (e: Error) => setError(e.message),
  })

  const run = useMutation({
    mutationFn: () => api.normalize(id, {
      sheetName: sheet!, headerRow, keyCol: config!.keyCol, pairs: config!.pairs, retranslateExisting: false,
    } satisfies NormalizeRequest),
    onSuccess: () => {
      setConfirmRerun(false)
      qc.invalidateQueries({ queryKey: ['normalize-current', id] })
      qc.invalidateQueries({ queryKey: ['project', id] })
      navigate(`/review/${id}`)
    },
    onError: (e: Error) => setError(e.message),
  })

  const disabled = !config || config.pairs.some((p) => !p.srcCol)

  return (
    <div className="flex min-h-screen flex-col gap-4 px-10 pt-9">
      <div className="flex items-center justify-between">
        <span className="text-sm text-muted">
          {project && <Link to={`/games/${project.gameId}`} className="text-ink hover:underline">{project.gameName}</Link>}
          {project && ' › '}
          {project?.fileName}
        </span>
        {(current.data?.configs.length ?? 0) > 0 && (
          <Button onClick={() => navigate(`/review/${id}`)}>검수로</Button>
        )}
      </div>

      <Card className="flex flex-col gap-3 p-4">
        <SheetChips
          chips={chips}
          active={adding ? null : sheet}
          addOptions={addOptions}
          adding={adding}
          confirmingRemove={confirmRemove}
          removing={removeMutation.isPending}
          onSelect={(name) => { setSheet(name); setConfig(null); setAdding(false); setConfirmRemove(null); setConfirmRerun(false) }}
          onStartAdd={() => { qc.invalidateQueries({ queryKey: ['normalize-current', id] }); setAdding(true); setConfirmRemove(null) }}
          onCancelAdd={() => setAdding(false)}
          onPickAdd={(name) => { setSheet(name); setConfig(null); setHeaderRow(1); setAdding(false); setConfirmRerun(false) }}
          onAskRemove={(name) => setConfirmRemove(name)}
          onCancelRemove={() => setConfirmRemove(null)}
          onConfirmRemove={(name) => removeMutation.mutate(name)}
        />

        <div className="flex items-center gap-2 text-xs text-muted">
          <label className="flex items-center gap-1.5 whitespace-nowrap">
            헤더 행
            <select aria-label="헤더 행" className={`${inputClass} w-20`} value={headerRow}
              onChange={(e) => { setHeaderRow(Number(e.target.value)); setConfig(null); setConfirmRerun(false) }}>
              {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>

        {error && (
          <div className="flex items-center justify-between rounded-md border border-orange bg-orangeSoft px-3 py-2 text-xs text-ink">
            <span>{error}</span>
            <button type="button" onClick={() => setError(null)} className="text-muted hover:text-ink">✕</button>
          </div>
        )}

        {active && config && (
          <ColumnRoleTable
            headers={active.headers}
            rows={active.preview}
            config={config}
            fixHeader={fix === 'NEW' ? (config.pairs[0]?.koHeader ?? 'KR_Fix') : null}
            onRole={(letter, value) => setConfig((c) => applyRole(c!, letter, value, fix))}
          />
        )}

        {config && (
          <div className="flex items-center gap-3">
            <Button onClick={() => setConfig((c) => ({
              ...c!,
              pairs: [...c!.pairs, { srcCol: '', enCol: null, koColMode: 'NEW', koCol: null, koHeader: defaultFixHeader(c!.pairs.length) }],
            }))}
            >
              + 원문·한국어 쌍 추가
            </Button>
          </div>
        )}

        {config && (
          <fieldset className="flex flex-wrap items-center gap-4 border-t border-line px-4 py-3 text-[13px]">
            <legend className="sr-only">수정 한국어(Fix) 쓸 곳</legend>
            <span className="font-semibold">수정 한국어(Fix) 쓸 곳</span>
            <label className="flex items-center gap-1.5">
              <input type="radio" name="fix" checked={fix === 'NEW'} onChange={() => pickFix('NEW')} />
              새 열로 추가
            </label>
            {fix === 'NEW' && (
              <input aria-label="새 열 이름" className={`${inputClass} !w-32`} value={config.pairs[0]?.koHeader ?? ''}
                onChange={(e) => setConfig((c) => ({ ...c!, pairs: c!.pairs.map((p, i) => (i === 0 ? { ...p, koHeader: e.target.value } : p)) }))} />
            )}
            <label className="flex items-center gap-1.5">
              <input type="radio" name="fix" checked={fix === 'OVERWRITE'} disabled={!canOverwrite(config)} onChange={() => pickFix('OVERWRITE')} />
              기존 한국어 열에 덮어쓰기
            </label>
            <span className="text-xs text-muted">검수 화면에는 둘 다 '기존 한국어'와 'Fix'가 따로 보입니다. 내보낼 때만 다릅니다.</span>
          </fieldset>
        )}

        {activeStats && (
          <div className="text-xs text-muted">
            {activeStats.normalized}행 검수 대상 · 건너뜀 {activeStats.skipped} · 기존 한국어 {activeStats.kept}행
          </div>
        )}

        {confirmRerun ? (
          <div className="flex items-center gap-2 rounded-md border border-orange bg-orangeSoft px-3 py-2 text-xs text-ink">
            <span>이 시트를 다시 정규화하면 수정한 번역·이력·담당·완료 체크가 모두 지워집니다.</span>
            <Button variant="danger" onClick={() => run.mutate()} disabled={run.isPending}>
              {run.isPending ? '정규화 중…' : '다시 정규화'}
            </Button>
            <Button onClick={() => setConfirmRerun(false)} disabled={run.isPending}>취소</Button>
          </div>
        ) : (
          <div>
            <Button
              variant="primary"
              disabled={disabled || run.isPending}
              onClick={() => { if (alreadyNormalized) setConfirmRerun(true); else run.mutate() }}
            >
              {run.isPending ? '정규화 중…' : '이 시트 정규화'}
            </Button>
          </div>
        )}
      </Card>
    </div>
  )
}
