import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { useProject, useProjectId } from '../api/hooks'
import type { ColumnPair, NormalizeRequest, SheetOption, SourceLang } from '../api/types'
import { DeepLInstructionsField } from '../components/DeepLInstructionsField'
import { useDeepLInstructions } from '../lib/deeplInstructions'
import { ColumnRoleTable } from '../components/normalize/ColumnRoleTable'
import { SheetChips } from '../components/normalize/SheetChips'
import { Button } from '../components/ui/Button'
import { AppHeader } from '../components/ui/AppHeader'
import { Card } from '../components/ui/Card'
import { inputClass } from '../components/ui/Field'
import { applyFinal, applyFixMode, applyRole, canOverwrite, defaultFixHeader, type FixMode, type RoleConfig } from '../lib/columnRoles'

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
  const instructions = useDeepLInstructions(project?.gameId)
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [headerRow, setHeaderRow] = useState(1)
  // 저장된 헤더 행을 이미 되살린 시트. 사용자가 헤더 행을 바꿔 설정을 다시 채울 때 저장값으로 되돌리지 않으려고 둔다.
  const [headerRestoredFor, setHeaderRestoredFor] = useState<string | null>(null)
  const [sheet, setSheet] = useState<string | null>(null)
  const [config, setConfig] = useState<RoleConfig | null>(null)
  const [fix, setFix] = useState<FixMode>('NEW')
  // 최종 리뷰 버전: 이미 고친 한국어 열(수정 한국어)도 읽는다. 켜야만 그 역할을 고를 수 있다.
  const [finalReview, setFinalReview] = useState(false)
  // 원문을 간체로 바꿔 넣기: 시트마다 저장된 값을 따른다.
  const [srcToSimplified, setSrcToSimplified] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState<string | null>(null)
  // 이미 행이 있는 시트를 다시 정규화하려는 걸 확인한다 — 확인 전엔 PUT을 보내지 않는다.
  const [confirmRerun, setConfirmRerun] = useState(false)
  // 정규화 뒤 한국어가 빈 행을 DeepL로 번역할지와 원문 언어(영어 열을 고른 쌍이 있을 때만 영어)
  const [translate, setTranslate] = useState(false)
  const [pickedLang, setSourceLang] = useState<SourceLang>('ZH')
  const hasEnglish = !!config?.pairs.some((p) => p.enCol)
  // 영어 열을 빼면 고른 값과 상관없이 중국어로 보낸다
  const sourceLang: SourceLang = hasEnglish ? pickedLang : 'ZH'

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
    setFinalReview(!!saved?.pairs.some((p) => p.fixCol))
    setSrcToSimplified(saved?.srcToSimplified ?? false)
    // 저장된 헤더 행도 되살린다(커넥터는 0·3 등으로 정규화할 수 있다). 시트를 고른 처음 한 번만 —
    // 사용자가 헤더 행을 바꿔 설정이 비워진 뒤엔 그 값을 지킨다. 저장된 설정이 없는 시트는 지금 값(기본 1)을 둔다.
    if (saved && headerRestoredFor !== active.name) setHeaderRow(saved.headerRow)
    setHeaderRestoredFor(active.name)
  }
  const savedHeaderRow = current.data?.configs.find((c) => c.config.sheetName === sheet)?.config.headerRow
  // 0~5 밖의 저장된 값(예: 7)도 고를 수 있게 보여 준다
  const headerOptions = [...new Set([0, 1, 2, 3, 4, 5, headerRow, ...(savedHeaderRow == null ? [] : [savedHeaderRow])])].sort((a, b) => a - b)
  // 덮어쓸 기존 한국어 열이 사라지면 모드를 되돌린다 — 렌더 단계에서 한 번만 참인 보정
  if (config && fix === 'OVERWRITE' && !canOverwrite(config)) {
    setFix('NEW')
    setConfig(applyFixMode(config, 'NEW'))
  }

  const pickFix = (m: FixMode) => { setFix(m); setConfig((c) => applyFixMode(c!, m)) }
  // 켜면 Fix는 수정 한국어 열에 쓰므로 덮어쓰기 모드를 새 열로 되돌려 둔다(끌 때 그 모드로 돌아간다).
  const pickFinal = (on: boolean) => {
    setFinalReview(on)
    if (on) setFix('NEW')
    setConfig((c) => applyFinal(on ? applyFixMode(c!, 'NEW') : c!, on, 'NEW'))
  }

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
      // 헤더 행도 기본값으로 — 지운 시트에서 되살린 값(예: 없음)이 다음 시트에 남으면 안 된다(저장된 설정이 있으면 위 보정이 다시 되살린다)
      if (sheet === sheetName) { setSheet(null); setConfig(null); setHeaderRow(1); setHeaderRestoredFor(null) }
    },
    onError: (e: Error) => setError(e.message),
  })

  const run = useMutation({
    mutationFn: async () => {
      const res = await api.normalize(id, {
        sheetName: sheet!, headerRow, keyCol: config!.keyCol, pairs: config!.pairs, retranslateExisting: false, srcToSimplified,
      } satisfies NormalizeRequest)
      // 정규화가 끝난 뒤에 번역을 건다 — 번역 작업은 백그라운드로 돌고 진행은 리뷰 화면 위 막대가 보여 준다
      if (translate) {
        await instructions.save()   // 고친 지시문을 게임에 저장한 뒤 번역을 건다
        await api.startTranslate(id, { sheet: sheet!, sourceLang })
      }
      return res
    },
    onSuccess: () => {
      setConfirmRerun(false)
      qc.invalidateQueries({ queryKey: ['normalize-current', id] })
      qc.invalidateQueries({ queryKey: ['project', id] })
      navigate(`/review/${id}`)
    },
    onError: (e: Error) => setError(e.message),
  })

  // 최종 리뷰 버전은 수정 한국어 열이 하나는 있어야 하고, 그 쌍엔 기존 한국어 열도 있어야 한다.
  const finalProblem = !finalReview || !config ? null
    : !config.pairs.some((p) => p.fixCol) ? '수정 한국어 열을 골라 주세요.'
    : config.pairs.some((p) => p.fixCol && !p.koCol) ? '수정 한국어 열과 함께 기존 한국어 열도 골라 주세요.'
    : null
  const disabled = !config || config.pairs.some((p) => !p.srcCol) || finalProblem !== null

  const configured = (current.data?.configs.length ?? 0) > 0
  const steps = [
    { label: '올리기', state: 'done' },
    { label: '정규화', state: 'now' },
    { label: '검수', state: configured ? 'ready' : 'todo' },
  ] as const

  return (
    <div className="flex min-h-screen flex-col">
      <AppHeader
        crumbs={project ? [{ label: project.gameName, to: `/games/${project.gameId}` }, { label: project.fileName }] : []}
        right={<>
          <ol aria-label="진행 단계" className="hidden items-center gap-1.5 text-xs md:flex">
            {steps.map((st, i) => (
              <li key={st.label} aria-current={st.state === 'now' ? 'step' : undefined}
                className={`rounded-full px-2.5 py-1 ${st.state === 'done' ? 'bg-greenSoft font-semibold text-green' : st.state === 'now' ? 'bg-primary font-semibold text-white' : 'bg-lineSoft text-body'}`}>
                {st.state === 'done' ? '✓ ' : `${i + 1} `}{st.label}
              </li>
            ))}
          </ol>
          {configured && <Button onClick={() => navigate(`/review/${id}`)}>검수로</Button>}
        </>}
      />

      <main className="mx-auto flex w-full max-w-[1232px] flex-wrap items-start gap-5 px-6 pb-16 pt-6">
        <section aria-label="시트와 열" className="flex min-w-0 flex-[999_1_640px] flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <SheetChips
              chips={chips}
              active={adding ? null : sheet}
              addOptions={addOptions}
              adding={adding}
              confirmingRemove={confirmRemove}
              removing={removeMutation.isPending}
              onSelect={(name) => { setSheet(name); setConfig(null); setHeaderRestoredFor(null); setAdding(false); setConfirmRemove(null); setConfirmRerun(false) }}
              onStartAdd={() => { qc.invalidateQueries({ queryKey: ['normalize-current', id] }); setAdding(true); setConfirmRemove(null) }}
              onCancelAdd={() => setAdding(false)}
              onPickAdd={(name) => { setSheet(name); setConfig(null); setHeaderRow(1); setHeaderRestoredFor(null); setAdding(false); setConfirmRerun(false) }}
              onAskRemove={(name) => setConfirmRemove(name)}
              onCancelRemove={() => setConfirmRemove(null)}
              onConfirmRemove={(name) => removeMutation.mutate(name)}
            />
            <label className="flex items-center gap-2 whitespace-nowrap text-[13px] text-body">
              헤더 행
              <select aria-label="헤더 행" className={`${inputClass} w-20`} value={headerRow}
                onChange={(e) => { setHeaderRow(Number(e.target.value)); setConfig(null); setConfirmRerun(false) }}>
                {headerOptions.map((n) => <option key={n} value={n}>{n === 0 ? '없음' : n}</option>)}
              </select>
            </label>
          </div>

          {error && (
            <div role="alert" className="flex items-center justify-between rounded-lg border border-orange bg-orangeSoft px-3 py-2 text-xs text-ink">
              <span>{error}</span>
              <button type="button" aria-label="오류 닫기" onClick={() => setError(null)} className="text-body hover:text-ink">✕</button>
            </div>
          )}

          <Card className="overflow-hidden">
            <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-lineSoft px-4 py-3">
              <h2 className="text-[15px] font-semibold">열마다 역할을 고르세요</h2>
              <span className="text-xs text-body">머리글 이름으로 미리 골라 두었습니다 · 앞 몇 행 미리 보기</span>
            </div>
            <div className="p-4">
              {active && config
                ? <ColumnRoleTable
                    headers={active.headers}
                    rows={active.preview}
                    config={config}
                    finalReview={finalReview}
                    fixHeader={!finalReview && fix === 'NEW' ? (config.pairs[0]?.koHeader ?? 'KR_Fix') : null}
                    onRole={(letter, value) => setConfig((c) => applyRole(c!, letter, value, fix))}
                  />
                : <p className="py-8 text-center text-[13px] text-body">시트를 읽는 중…</p>}
            </div>
            {config && (
              <div className="border-t border-lineSoft px-4 py-3">
                <Button onClick={() => setConfig((c) => ({
                  ...c!,
                  pairs: [...c!.pairs, { srcCol: '', enCol: null, koColMode: 'NEW', koCol: null, koHeader: defaultFixHeader(c!.pairs.length) }],
                }))}
                >
                  + 원문·한국어 쌍 추가
                </Button>
              </div>
            )}
          </Card>
        </section>

        <aside aria-label="정리 결과와 시작" className="flex flex-[1_1_320px] flex-col gap-3 lg:sticky lg:top-20">
          <Card className="flex flex-col gap-4 p-4">
            <h2 className="text-[15px] font-semibold">{sheet ? `${sheet} 시트` : '시트'}</h2>
            {activeStats && (
              <dl className="grid grid-cols-3 gap-2">
                {([['검수 대상', activeStats.normalized], ['기존 한국어', activeStats.kept], ['건너뜀', activeStats.skipped]] as const).map(([k, v]) => (
                  <div key={k} className="rounded-lg bg-canvas px-2.5 py-2">
                    <dd className="text-lg font-bold tabular-nums">{v.toLocaleString()}</dd>
                    <dt className="text-[11px] text-body">{k}</dt>
                  </div>
                ))}
              </dl>
            )}

            {config && finalReview && (
              <div className="flex flex-col gap-1 text-[13px]">
                <span className="font-semibold">수정 한국어(Fix)</span>
                <span className="text-xs leading-relaxed text-body">
                  기존 한국어는 원래 값으로, 수정 한국어는 지금 Fix로 들어갑니다. 내보낼 때 Fix는 수정 한국어 열에 씁니다.
                </span>
                {finalProblem && <p className="text-xs text-orange">{finalProblem}</p>}
              </div>
            )}

            {config && !finalReview && (
              <fieldset className="flex flex-col gap-2 text-[13px]">
                <legend className="mb-2 font-semibold">고친 한국어(Fix)를 내보낼 곳</legend>
                <label className="flex items-center gap-2">
                  <input type="radio" name="fix" checked={fix === 'NEW'} onChange={() => pickFix('NEW')} />
                  새 열로 추가
                  {fix === 'NEW' && (
                    <input aria-label="새 열 이름" className={`${inputClass} !h-7 !w-28 !py-0 text-xs`} value={config.pairs[0]?.koHeader ?? ''}
                      onChange={(e) => setConfig((c) => ({ ...c!, pairs: c!.pairs.map((p, i) => (i === 0 ? { ...p, koHeader: e.target.value } : p)) }))} />
                  )}
                </label>
                <label className="flex items-center gap-2">
                  <input type="radio" name="fix" checked={fix === 'OVERWRITE'} disabled={!canOverwrite(config)} onChange={() => pickFix('OVERWRITE')} />
                  기존 한국어 열에 덮어쓰기
                </label>
                <span className="text-xs text-body">검수 화면에는 둘 다 기존 한국어와 Fix가 따로 보입니다.</span>
              </fieldset>
            )}

            {config && (
              <div className="flex flex-col gap-2 border-t border-lineSoft pt-4 text-[13px]">
                <label className="flex items-center justify-between gap-3 font-semibold">
                  <span>정규화한 뒤 한국어가 빈 행을 DeepL로 번역</span>
                  <input type="checkbox" className="h-4 w-4" checked={translate} onChange={(e) => setTranslate(e.target.checked)} />
                </label>
                {translate && (
                  <div className="flex flex-col gap-2">
                    <select aria-label="번역 원문" className={`${inputClass} w-40`} value={sourceLang} onChange={(e) => setSourceLang(e.target.value as SourceLang)}>
                      <option value="ZH">중국어 원문</option>
                      <option value="EN" disabled={!hasEnglish}>영어 원문</option>
                    </select>
                    <span className="text-xs leading-relaxed text-body">
                      {sourceLang === 'EN' ? '영어로 번역하면 단어장(중국어 기준)이 적용되지 않습니다.' : '게임 단어장을 DeepL에 같이 보냅니다. 같은 원문은 한 번만 번역합니다.'}
                    </span>
                    <DeepLInstructionsField text={instructions.text} onChange={instructions.setText} />
                  </div>
                )}
              </div>
            )}

            {config && (
              <label className="flex items-start gap-2 border-t border-lineSoft pt-4 text-[13px] text-body">
                <input type="checkbox" className="mt-0.5" checked={finalReview} onChange={(e) => pickFinal(e.target.checked)} />
                <span>최종 리뷰 버전 <span className="block text-xs">이미 고친 한국어 열(수정 한국어)도 읽어 Fix로 넣습니다</span></span>
              </label>
            )}

            {config && (
              <label className="flex items-start gap-2 border-t border-lineSoft pt-4 text-[13px] text-body">
                <input type="checkbox" className="mt-0.5" checked={srcToSimplified} onChange={(e) => setSrcToSimplified(e.target.checked)} />
                <span>원문을 간체로 바꿔 넣기 <span className="block text-xs">번체 원문을 간체 중국어로 바꿔 정규화합니다</span></span>
              </label>
            )}

            {confirmRerun ? (
              <div role="alert" className="flex flex-col gap-2 rounded-lg border border-orange bg-orangeSoft p-3 text-xs text-ink">
                <span>이 시트를 다시 정규화하면 수정한 번역·이력·담당·완료 체크가 모두 지워집니다.</span>
                <div className="flex gap-2">
                  <Button variant="danger" onClick={() => run.mutate()} disabled={run.isPending}>
                    {run.isPending ? '정규화 중…' : '다시 정규화'}
                  </Button>
                  <Button onClick={() => setConfirmRerun(false)} disabled={run.isPending}>취소</Button>
                </div>
              </div>
            ) : (
              <Button
                variant="primary"
                className="h-11 w-full justify-center !text-[14px] !font-semibold"
                disabled={disabled || run.isPending}
                onClick={() => { if (alreadyNormalized) setConfirmRerun(true); else run.mutate() }}
              >
                {run.isPending ? '정규화 중…' : alreadyNormalized ? '이 시트 다시 정규화' : '이 시트 정규화'}
              </Button>
            )}
            {alreadyNormalized && !confirmRerun && (
              <p className="-mt-2 text-xs text-body">이미 정규화한 시트입니다. 다시 하면 고친 번역이 지워집니다 — 검수만 하려면 위의 「검수로」.</p>
            )}
          </Card>
        </aside>
      </main>
    </div>
  )
}
