import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api/client'
import type { Basis } from '../api/types'
import { AutoTextarea } from './ui/AutoTextarea'
import { Button } from './ui/Button'
import { Card } from './ui/Card'
import { Chip } from './ui/Chip'
import { inputClass } from './ui/Field'

const SOURCE_LABEL: Record<string, string> = {
  DEEPL: 'DeepL', CACHE: 'DeepL (캐시 · 다시 사지 않음)', GLOSSARY: '단어장 (문장 통째)', MEMORY: '번역 메모리', LLM: 'GPT',
}

/** 파일 없이 문장 하나를 이 게임의 단어장·번역 메모리에 맞춰 DeepL로 번역한다. 결과는 어디에도 저장되지 않는다. */
export function QuickTranslate({ gameId }: { gameId: number }) {
  const [text, setText] = useState('')
  const [en, setEn] = useState('')
  const [basis, setBasis] = useState<Basis>('CN_ONLY')
  const [copied, setCopied] = useState(false)
  const run = useMutation({
    mutationFn: () => api.games.quickTranslate(gameId, { text: text.trim(), en: en.trim() || undefined, basis }),
    onMutate: () => setCopied(false),
  })
  const r = run.data
  const glossary = r ? Object.entries(r.glossary) : []
  const canRun = text.trim().length > 0 && !run.isPending
  const copy = async () => {
    if (!r) return
    try { await navigator.clipboard.writeText(r.ko); setCopied(true) } catch { /* 클립보드 권한이 없으면 직접 선택해서 복사 */ }
  }

  return (
    <Card>
      <div className="flex items-center justify-between px-5 py-3">
        <span className="text-base font-semibold">한 문장 번역</span>
        <span className="text-xs text-muted">이 게임의 단어장을 DeepL에 같이 보냅니다 · 같은 문장은 캐시 · 결과는 저장되지 않습니다</span>
      </div>
      <form className="flex flex-col gap-2 border-t border-lineSoft px-5 py-4" onSubmit={(e) => { e.preventDefault(); if (canRun) run.mutate() }}>
        <AutoTextarea aria-label="번역할 문장" className={`${inputClass} leading-relaxed`} placeholder="간체 원문 (예: 霜语者加入了队伍) · Ctrl/⌘+Enter로 번역"
          value={text} onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && canRun) { e.preventDefault(); run.mutate() } }} />
        <div className="flex flex-wrap items-center gap-2">
          <input aria-label="영어 문장 (선택)" className={`${inputClass} min-w-[240px] flex-1`} placeholder="영어 문장 (선택)" value={en} onChange={(e) => setEn(e.target.value)} />
          <select aria-label="번역 기준" className={`${inputClass} w-40`} value={basis} onChange={(e) => setBasis(e.target.value as Basis)}>
            <option value="CN_ONLY">간체 기준</option><option value="EN_FIRST">영어 우선</option>
          </select>
          <Button type="submit" variant="primary" disabled={!canRun}>{run.isPending ? '번역 중…' : '번역'}</Button>
        </div>
        {run.error && <p className="text-xs text-orange">{(run.error as Error).message}</p>}
      </form>
      {r && (
        <div className="flex flex-col gap-2 border-t border-lineSoft bg-canvas px-5 py-4">
          <div className="flex items-start gap-3">
            <p aria-label="번역 결과" className="flex-1 whitespace-pre-wrap text-[15px] leading-relaxed">{r.ko}</p>
            <Chip color={r.source === 'MEMORY' || r.source === 'GLOSSARY' ? 'green' : 'blue'}>{SOURCE_LABEL[r.source] ?? r.source}</Chip>
            <Button onClick={copy} className="!px-2.5 !py-1 !text-xs">{copied ? '복사됨' : '복사'}</Button>
          </div>
          {!r.placeholderOk && <p className="text-xs font-semibold text-orange">자리표시자·태그가 원문과 다릅니다. 확인하세요.</p>}
          {r.problem === 'HAN' && <p className="text-xs font-semibold text-orange">DeepL 번역에 중국어가 남았습니다. 확인하세요.</p>}
          {r.termMiss.length > 0 && <p className="text-xs font-semibold text-purple">용어 누락: {r.termMiss.join(' · ')}</p>}
          <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted">
            <span>적용한 용어</span>
            {glossary.length === 0 ? <span>없음 (이 문장에 나오는 확정 용어가 없습니다)</span> : glossary.map(([src, ko]) => <Chip key={src} color="purple">{src} → {ko}</Chip>)}
          </div>
          {r.references.length > 0 && (
            <details className="text-xs text-muted">
              <summary className="cursor-pointer">참고한 기존 번역 {r.references.length}개</summary>
              <ul className="mt-1 flex flex-col gap-1">
                {r.references.map((ref) => <li key={ref.cn}>{ref.cn} → <span className="text-ink">{ref.ko}</span></li>)}
              </ul>
            </details>
          )}
        </div>
      )}
    </Card>
  )
}
