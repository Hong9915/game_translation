import { useQuery } from '@tanstack/react-query'
import { useDeferredValue, useState } from 'react'
import { api } from '../../api/client'
import type { GlossaryPhraseCategory, GlossaryVariant, GlossaryVariantKind } from '../../api/types'
import type { SpacingPolicy } from '../../lib/spacing'
import { SpacingBulkDialog } from './SpacingBulkDialog'
import { UnifyDialog } from './UnifyDialog'
import { VariantCard } from './VariantCard'

const KINDS: { kind: GlossaryVariantKind; label: string }[] = [
  { kind: 'ALT', label: '다른 표기' }, { kind: 'SPACING', label: '띄어쓰기' }, { kind: 'MISSING', label: '주 표기 빠짐' },
]

/** 분류 필터 칩 순서. 고유명사 → 수치·일반 낱말 → 문법 조각. */
const CATEGORIES: { key: GlossaryPhraseCategory; label: string }[] = [
  { key: 'CHARACTER', label: '캐릭터' }, { key: 'ITEM', label: '아이템' }, { key: 'SKILL', label: '스킬' }, { key: 'PLACE', label: '장소·모드' },
  { key: 'NAME_OTHER', label: '기타 고유명사' }, { key: 'STAT', label: '능력치' }, { key: 'NOUN', label: '일반명사' }, { key: 'VERB', label: '동사' },
  { key: 'COLOR', label: '색깔' }, { key: 'MODIFIER', label: '수식어' }, { key: 'FUNCTION', label: '기능어' }, { key: 'PHRASE', label: '구절 조각' },
]
type CategoryFilter = 'ALL' | GlossaryPhraseCategory | 'NONE'

interface Props { gameId: number }

/**
 * 추천 창의 표기 갈림 탭(설계 2026-10-07 §3). 게임 전체를 훑는 무거운 계산이라 처음 열 때 한 번만 받는다(창 포커스로 다시 받지 않는다).
 * 처리한 카드는 그 자리에서 접히고, 목록을 새로 받으면 단어장에 있는 구절이라 빠진다.
 */
export function VariantsTab({ gameId }: Props) {
  const list = useQuery({ queryKey: ['glossary', gameId, 'VARIANTS'], queryFn: () => api.glossary.variants(gameId), staleTime: Infinity, refetchOnWindowFocus: false })
  const [kind, setKind] = useState<GlossaryVariantKind>('ALT')
  const [search, setSearch] = useState('')
  const [done, setDone] = useState<ReadonlyMap<string, string>>(new Map())
  const onDone = (find: string, message: string) => setDone((m) => new Map(m).set(find, message))
  const [unifying, setUnifying] = useState<GlossaryVariant | null>(null)
  const [policy, setPolicy] = useState<SpacingPolicy>('frequent')
  const [bulk, setBulk] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const q = useDeferredValue(search).trim().toLowerCase()
  const all = list.data ?? []
  // 분류: 모든 종류의 카드를 한 번에 묻는다. 서버가 처음 보는 구절만 GPT에 묻고 저장하므로 두 번째부터는 바로 온다.
  const categories = useQuery({
    queryKey: ['glossary', gameId, 'CATEGORIES', all.length],
    queryFn: () => api.glossary.categories(gameId, all.map((v) => ({ src: v.find, ko: v.main.ko, example: v.main.examples[0]?.src ?? null }))),
    enabled: all.length > 0, staleTime: Infinity, refetchOnWindowFocus: false, retry: false,
  })
  const [category, setCategory] = useState<CategoryFilter>('ALL')
  const categoryOf = (v: GlossaryVariant): GlossaryPhraseCategory | 'NONE' => categories.data?.[v.find] ?? 'NONE'
  const ofKind = all.filter((v) => v.kind === kind)
  const ofCategory = category === 'ALL' ? ofKind : ofKind.filter((v) => categoryOf(v) === category)
  const shown = ofCategory.filter((v) => !q || [v.find, v.main.ko, ...v.alts.map((a) => a.ko)].some((t) => t.toLowerCase().includes(q)))
  const categoryCount = (c: CategoryFilter) => (c === 'ALL' ? ofKind.length : ofKind.filter((v) => categoryOf(v) === c).length)
  // 참고용 DeepL 직역: 보고 있는 종류의 원문을 한 번에 묻는다(검색과 무관하게 같은 묶음이라 다시 묻지 않는다). 실패하면 안 띄울 뿐이다.
  const finds = ofKind.map((v) => v.find)
  const literal = useQuery({
    queryKey: ['glossary', 'LITERAL', finds], queryFn: () => api.glossary.literal(finds),
    enabled: finds.length > 0, staleTime: Infinity, refetchOnWindowFocus: false, retry: false,
  })

  const spacingLeft = kind === 'SPACING' ? shown.filter((v) => !done.has(v.find)) : []

  return (
    <div>
      {/* 스크롤해도 종류 칩·검색이 보이게 창 머리말(높이는 --recommend-header) 바로 아래에 붙인다 */}
      <div className="sticky z-[5] -mx-8 flex flex-wrap items-center gap-2 border-b border-lineSoft bg-white px-8 py-3"
        style={{ top: 'var(--recommend-header, 0px)' }}>
        {KINDS.map((k) => (
          <button key={k.kind} type="button" aria-pressed={kind === k.kind} onClick={() => setKind(k.kind)}
            className={`press rounded-full border px-3 py-1 text-[13px] ${kind === k.kind ? 'border-primary font-semibold text-primary' : 'border-line text-body'}`}>
            {k.label} {all.filter((v) => v.kind === k.kind).length}
          </button>
        ))}
        <input aria-label="원문·표기 검색" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="원문·표기 검색"
          className="ml-auto w-56 rounded-lg border border-line bg-white px-3 py-1.5 text-[13px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/10" />
        <div role="group" aria-label="분류" className="flex w-full flex-wrap items-center gap-1.5 text-[12px]">
          <span className="mr-1 text-muted">분류</span>
          {categories.isLoading ? <span className="text-muted">분류하는 중… (처음 한 번만 GPT로 매깁니다)</span>
            : categories.isError ? <span className="text-orange">분류를 불러오지 못했습니다</span>
            : ([{ key: 'ALL', label: '전체' }, ...CATEGORIES, { key: 'NONE', label: '미분류' }] as { key: CategoryFilter; label: string }[])
              .filter((c) => c.key === 'ALL' || c.key === category || categoryCount(c.key) > 0)
              .map((c) => (
                <button key={c.key} type="button" aria-pressed={category === c.key} onClick={() => setCategory(c.key)}
                  className={`press rounded-full px-2.5 py-0.5 ${category === c.key ? 'bg-primarySoft font-semibold text-primary' : 'bg-canvas text-body hover:bg-lineSoft'}`}>
                  {c.label} {categoryCount(c.key)}
                </button>
              ))}
        </div>
        {kind === 'SPACING' && (
          <div className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 text-[13px] text-body">
            <span className="text-muted">맞출 쪽</span>
            <label className="flex cursor-pointer items-center gap-1.5">
              <input type="radio" name="spacing-policy" checked={policy === 'frequent'} onChange={() => setPolicy('frequent')} />많이 쓴 꼴
            </label>
            <label className="flex cursor-pointer items-center gap-1.5" title="Kiwi 띄어쓰기 교정 결과. 표준 꼴이 없는 구절은 많이 쓴 꼴로 맞춘다">
              <input type="radio" name="spacing-policy" checked={policy === 'standard'} onChange={() => setPolicy('standard')} />표준 띄어쓰기
            </label>
            <button type="button" disabled={spacingLeft.length === 0} onClick={() => { setNotice(null); setBulk(true) }}
              className="press ml-auto rounded-lg border border-primary px-3 py-1 text-[13px] font-medium text-primary disabled:border-line disabled:text-muted">
              한꺼번에 통일…
            </button>
          </div>
        )}
      </div>
      {notice && <p role="status" className="mt-3 rounded-lg bg-[#ecfdf5] px-3 py-2 text-[13px] text-[#065f46]">{notice}</p>}
      {list.isLoading ? <p className="py-16 text-center text-muted">게임 전체에서 표기가 갈린 구절을 찾는 중…</p>
        : list.isError ? <p role="alert" className="py-16 text-center text-orange">표기 갈림을 불러오지 못했습니다. {list.error.message}</p>
        : shown.length === 0 ? <p className="py-16 text-center text-muted">없음</p>
        : shown.map((v) => <VariantCard key={v.find} gameId={gameId} v={v} literal={literal.data?.[v.find]} done={done.get(v.find)} onDone={onDone} onUnify={setUnifying} />)}
      {unifying && (
        <UnifyDialog gameId={gameId} v={unifying} onClose={() => setUnifying(null)}
          onDone={(message) => { onDone(unifying.find, message); setUnifying(null) }} />
      )}
      {bulk && (
        <SpacingBulkDialog gameId={gameId} variants={spacingLeft} policy={policy} onClose={() => setBulk(false)}
          onDone={(fixed, applied) => {
            setDone((m) => { const n = new Map(m); for (const f of fixed) n.set(f.find, `띄어쓰기 통일, ${f.ko}`); return n })
            setNotice(`띄어쓰기 ${fixed.length.toLocaleString()}개 구절에서 ${applied.toLocaleString()}행을 고쳤습니다.`)
            setBulk(false)
          }} />
      )}
    </div>
  )
}
