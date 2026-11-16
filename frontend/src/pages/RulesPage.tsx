import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { CorrectionRule, RuleList } from '../api/types'
import type { ReviewTarget } from '../lib/reviewTarget'
import { RulePreview } from '../components/rules/RulePreview'
import { TagSpacingDialog } from '../components/workspace/TagSpacingDialog'
import { Button } from '../components/ui/Button'

/** 글 종류 코드(설계 2026-11-13 §3). */
const KIND: Record<string, string> = {
  E: '효과(스킬·버프·장비)', I: '아이템·획득 안내', Q: '조건·퀘스트', S: '시스템 알림', H: '도움말·규칙',
  T: '이야기·배경', D: '대사', N: '공지·메일', U: '이름·UI',
}
const kindName = (r: CorrectionRule) => KIND[r.textKind ?? ''] ?? r.textKind ?? ''
/** 줄 끝 마침표 빼기(2026-11-16) — 글 종류 상관없이 게임 전체에 하나. */
const isPeriod = (r: CorrectionRule) => r.type === 'PERIOD'
const ruleName = (r: CorrectionRule) => (isPeriod(r) ? '줄 끝 마침표 빼기' : kindName(r))
const ruleTitle = (r: CorrectionRule) => (isPeriod(r) ? '줄 끝 마침표 빼기 · 게임 전체' : `서술식 → 개조식 · ${kindName(r)}`)
const pct = (n: number) => `${Math.round(n * 100)}%`

/**
 * 교정 규칙 창(설계 2026-11-13 §5, 2026-11-16 찾기로 바꿈) — 글 종류마다 서술식이 남은 행을 찾아 보여 주고, 미리 보기에서
 * 골라 개조식으로 적용한다. 손 수정 비율은 있으면 참고로만 보여 준다(배운 비율로 거르지 않는다).
 */
export function RulesPage() {
  const gameId = Number(useParams<{ gameId: string }>().gameId)
  const qc = useQueryClient()
  const [open, setOpen] = useState<CorrectionRule | null>(null)
  const [jobId, setJobId] = useState<number | null>(null)
  const [tagOpen, setTagOpen] = useState(false)
  const [tagFixed, setTagFixed] = useState(false)

  const game = useQuery({ queryKey: ['game', gameId], queryFn: () => api.games.get(gameId) })
  const list = useQuery({ queryKey: ['rules', gameId], queryFn: () => api.rules.list(gameId) })
  // 태그 띄어쓰기(2026-11-17) — 게임 전체를 Kiwi로 보느라 느려서 이 줄만 따로 불러온다. 미리 보기 창도 같은 키를 쓴다.
  const tagTarget = useMemo<ReviewTarget>(() => ({ kind: 'game', gameId, sheetIds: [] }), [gameId])
  const tags = useQuery({ queryKey: ['tag-spacing', tagTarget], queryFn: () => api.review(tagTarget).tagSpacing(),
    staleTime: Infinity, refetchOnWindowFocus: false, retry: false })
  const closeTags = () => {
    setTagOpen(false)
    if (tagFixed) { setTagFixed(false); void qc.invalidateQueries({ queryKey: ['tag-spacing', tagTarget] }) }
  }
  useEffect(() => { if (game.data) document.title = `교정 규칙 — ${game.data.name}` }, [game.data])

  const put = (l: RuleList) => qc.setQueryData(['rules', gameId], l)
  const relearn = useMutation({ mutationFn: () => api.rules.relearn(gameId), onSuccess: put })
  const judge = useMutation({ mutationFn: () => api.rules.judgeKinds(gameId), onSuccess: (j) => setJobId(j.id) })
  const job = useQuery({
    queryKey: ['job', jobId], queryFn: () => api.rules.job(jobId!), enabled: jobId != null,
    refetchInterval: (q) => (q.state.data && q.state.data.status !== 'RUNNING' ? false : 2000),
  })
  useEffect(() => {
    if (job.data && job.data.status !== 'RUNNING') { setJobId(null); void relearn.mutateAsync() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job.data?.status])
  // 창을 열면 한 번 찾는다 — 버튼을 누르지 않아도 지금 남은 행이 보이게(2026-11-16)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { relearn.mutate() }, [gameId])

  // 서술식이 남은 종류는 손 수정 비율과 상관없이 다 보여 준다(2026-11-16). 무시한 것과 남은 행 없는 것만 뺀다.
  // 줄 끝 마침표 빼기는 맨 위, 문체 규칙은 남은 행 많은 순
  const all = (list.data?.rules ?? []).filter((r) => (r.type === 'STYLE' || r.type === 'PERIOD') && r.status !== 'IGNORED'
      && (r.candidates > 0 || r.status === 'APPROVED'))
    .sort((a, b) => Number(isPeriod(b)) - Number(isPeriod(a)) || b.candidates - a.candidates)
  const periods = all.filter(isPeriod)
  const styles = all.filter((r) => !isPeriod(r))
  const tagCount = tags.data?.length ?? 0

  const row = (r: CorrectionRule) => (
    <li key={r.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-line px-4 py-3">
      <span className="font-medium">{ruleName(r)}</span>
      {isPeriod(r) ? <span className="text-muted">줄바꿈 앞·글 끝의 "." (말줄임표는 그대로)</span> : <span>서술식 → 개조식</span>}
      <span className="tabular-nums">{isPeriod(r) ? '남은' : '서술식 남은'} {r.candidates}행</span>
      {r.converted + r.kept > 0 && (
        <span className="tabular-nums text-muted">사람이 바꾼 비율 {pct(r.ratio)} (바꿈 {r.converted} / 둠 {r.kept})
          {r.converted > 0 && ` · 사람 결과와 같음 ${pct(r.sameAsHuman)}`}</span>
      )}
      {r.status === 'APPROVED' && <span className="text-xs text-muted">적용 {r.appliedCount}행</span>}
      <Button className="ml-auto" onClick={() => setOpen(r)} disabled={r.candidates === 0}>미리 보기</Button>
    </li>
  )

  return (
    <div className="min-h-screen bg-white text-sm text-ink">
      <header className="sticky top-0 z-10 border-b border-line bg-white px-8 pb-3 pt-5">
        <h1 className="text-lg font-semibold leading-tight">교정 규칙 — {game.data?.name ?? '…'}</h1>
        <p className="mt-1 break-keep text-xs text-muted">글 종류마다 "~습니다"처럼 서술식이 남은 행을 찾아 보여 줍니다. 미리 보기에서 골라 개조식(~함·~됨·명사)으로 바꿉니다. 대사·공지·메일은 바꾸지 않습니다. 맨 위 "줄 끝 마침표 빼기"와 "태그 띄어쓰기"는 대사를 포함한 게임 전체 글이 대상입니다.</p>
        <div className="mt-2 flex flex-wrap items-center gap-3 text-[13px]">
          <Button onClick={() => { relearn.mutate(); void tags.refetch() }} disabled={relearn.isPending}>{relearn.isPending ? '찾는 중…' : '다시 찾기'}</Button>
          <span className="text-muted">마지막 {list.data?.learnedAt ? new Date(list.data.learnedAt).toLocaleString('ko-KR') : '없음'}</span>
          {(list.data?.unjudgedRows ?? 0) > 0 && (
            <Button onClick={() => judge.mutate()} disabled={judge.isPending || jobId != null}
              title="서술식 어미가 있는 행의 글 종류를 GPT로 한 번 판단합니다. 결과는 저장되어 다시 묻지 않습니다.">
              {jobId != null ? `판단하는 중… ${job.data?.doneBatches ?? 0}/${job.data?.totalBatches ?? '?'}`
                : `글 종류 판단하기 · ${list.data!.unjudgedRows.toLocaleString()}행 · 약 $${list.data!.estimateUsd.toFixed(2)}`}
            </Button>
          )}
        </div>
      </header>

      <main className="px-8 pb-16 pt-2">
        {list.isLoading ? <p className="py-16 text-center text-muted">불러오는 중…</p>
          : list.isError ? <p role="alert" className="py-16 text-center text-orange">규칙을 불러오지 못했습니다. {list.error.message}</p>
          : (
            <>
              <ul className="mt-2 space-y-2">
                {periods.map(row)}
                <li aria-label="태그 띄어쓰기" className="flex flex-wrap items-center gap-3 rounded-xl border border-line px-4 py-3">
                  <span className="font-medium">태그 띄어쓰기</span>
                  <span className="text-muted">태그 앞뒤에 붙은 낱말 띄우기 (Kiwi)</span>
                  {tags.isFetching ? <span className="text-muted">찾는 중…</span>
                    : tags.isError ? <span role="alert" className="text-orange">{tags.error.message}</span>
                    : <span className="tabular-nums">남은 {tagCount}행</span>}
                  <Button className="ml-auto" onClick={() => setTagOpen(true)} disabled={tags.isFetching || tagCount === 0}>미리 보기</Button>
                </li>
                {styles.map(row)}
              </ul>
              {styles.length === 0 && <p className="break-keep py-12 text-center text-muted">{relearn.isPending ? '서술식 남은 행을 찾는 중…' : (list.data?.unjudgedRows ?? 0) > 0 ? '글 종류를 아직 모르는 행이 있습니다. "글 종류 판단하기"를 눌러 주세요.' : '서술식이 남은 행이 없습니다.'}</p>}
            </>
          )}
      </main>
      {tagOpen && <TagSpacingDialog target={tagTarget} label="게임 전체" onFixed={(n) => { if (n > 0) setTagFixed(true) }} onClose={closeTags} />}
      {open && <RulePreview gameId={gameId} rule={open} title={ruleTitle(open)} onClose={() => setOpen(null)} />}
    </div>
  )
}
