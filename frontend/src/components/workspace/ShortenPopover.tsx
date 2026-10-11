import { useEffect, useRef, useState } from 'react'
import type { RowShortenResult } from '../../api/types'
import { Anchored } from '../ui/Anchored'

export interface ShortenPopoverProps {
  x: number
  y: number
  /** maxChars가 없으면 서버가 원문 글자 수 × 비율로 목표를 정한다. */
  onShorten: (maxChars?: number) => Promise<RowShortenResult>
  /** 후보를 고르면 부른다. 저장은 부모가 일반 저장으로 한다. */
  onPick: (ko: string) => void
  onClose: () => void
}

/**
 * 우클릭 "짧게 줄이기" 창. 열리자마자 기본 목표로 GPT 후보를 받고, 목표 글자 수를 바꿔 다시 받을 수 있다.
 * GPT는 글자 수를 잘 못 맞추므로 서버가 센 글자 수를 후보마다 보이고 목표를 넘은 후보는 주황색으로 표시한다(버리지는 않는다).
 * 바깥을 누르거나 Esc를 누르면 닫힌다. 화면 가장자리에서 열려도 창이 화면 안에 들어오게 자리를 옮긴다.
 */
export function ShortenPopover({ x, y, onShorten, onPick, onClose }: ShortenPopoverProps) {
  const [result, setResult] = useState<RowShortenResult | null>(null)
  const [target, setTarget] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 늦게 온 옛 응답이 새 응답을 덮지 않게 요청마다 번호를 붙인다
  const seq = useRef(0)
  const ref = useRef<HTMLDivElement>(null)

  const ask = (maxChars?: number) => {
    const mine = ++seq.current
    setLoading(true)
    setError(null)
    onShorten(maxChars).then(
      (r) => { if (mine !== seq.current) return; setResult(r); setTarget(String(r.target)); setLoading(false) },
      (e: unknown) => { if (mine !== seq.current) return; setError(e instanceof Error ? e.message : '줄이지 못했습니다.'); setLoading(false) },
    )
  }
  // 열릴 때 한 번만 묻는다
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { ask(); return () => { seq.current++ } }, [])

  // 바깥 클릭·Esc로 닫는다. Esc는 캡처 단계에서 잡아 그리드의 편집 키로 새지 않게 한다(우클릭 메뉴와 같은 방식).
  useEffect(() => {
    const onDown = (e: globalThis.MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) onClose() }
    const onEsc = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); onClose() } }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc, true)
    return () => { document.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onEsc, true) }
  }, [onClose])

  const retry = () => {
    const n = Number(target)
    ask(Number.isInteger(n) && n > 0 ? n : undefined)
  }
  const goal = result?.target ?? 0

  return (
    // 이 창의 키·붙여넣기가 그리드(onKeyDown·onPaste)로 올라가 Fix 편집을 열지 않게 여기서 멈춘다.
    // 후보가 오면 창 높이가 바뀌므로(deps) 그때마다 화면 안으로 다시 맞춘다
    <Anchored ref={ref} role="dialog" aria-label="짧게 줄이기" x={x} y={y} deps={[loading, result, error]}
      onKeyDown={(e) => e.stopPropagation()}
      onPaste={(e) => e.stopPropagation()}
      className="glass pop-in z-50 w-80 origin-top-left rounded-xl border border-line/70 p-2.5 text-[13px] shadow-[0_12px_32px_-8px_rgba(16,24,40,0.22)]">
      <div className="mb-2 flex items-center gap-1.5 text-xs text-body">
        {result && <span>현재 {result.len}자 →</span>}
        <label htmlFor="shorten-target">목표</label>
        <input id="shorten-target" type="number" min={1} aria-label="목표 글자 수" value={target} autoFocus
          onChange={(e) => setTarget(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) { e.preventDefault(); retry() } }}
          className="w-14 rounded-md border border-line bg-white px-1.5 py-0.5 text-right text-[13px] outline-none focus:border-[#107c41]" />
        <span>자</span>
        <button type="button" onClick={retry} disabled={loading}
          className="press ml-auto rounded-md px-2 py-0.5 text-body hover:bg-black/5 disabled:text-muted">다시 줄이기</button>
      </div>
      {loading ? (
        <div className="px-1 py-3 text-center italic text-muted">GPT가 줄이는 중…</div>
      ) : error ? (
        <div role="alert" className="px-1 py-2 text-orange">{error}</div>
      ) : result && (
        <ul className="space-y-0.5">
          {result.candidates.map((c) => (
            <li key={c.ko}>
              <button type="button" onClick={() => onPick(c.ko)}
                className="flex w-full items-baseline gap-2 rounded-md px-2 py-1 text-left hover:bg-primarySoft active:bg-[#d5e1f6]">
                <span className="min-w-0 flex-1 break-keep [overflow-wrap:anywhere]">{c.ko}</span>
                <span className={`shrink-0 text-[11px] ${c.len > goal ? 'text-orange' : 'text-muted'}`} {...(c.len > goal ? { 'data-over': '' } : {})}>{c.len}자</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-1.5 flex items-center justify-between">
        <span className="text-[11px] text-muted">고르면 Fix에 저장됩니다</span>
        <button type="button" onClick={onClose} className="press rounded-md px-2.5 py-1 text-body hover:bg-black/5">닫기</button>
      </div>
    </Anchored>
  )
}
