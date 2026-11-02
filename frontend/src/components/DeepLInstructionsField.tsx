import { instructionsProblem, MAX_CHARS, MAX_LINES } from '../lib/deeplInstructions'
import { inputClass } from './ui/Field'

/** DeepL 지시문 입력 칸. 값과 저장은 useDeepLInstructions가 맡는다. */
export function DeepLInstructionsField({ text, onChange, autoFocus }: { text: string; onChange: (t: string) => void; autoFocus?: boolean }) {
  const problem = instructionsProblem(text)
  return (
    <label className="flex w-full flex-col gap-1 text-xs text-muted">
      <span>DeepL 지시문 · 한 줄에 하나, {MAX_LINES}줄·줄당 {MAX_CHARS}자까지 · 이 게임의 다음 번역에도 쓰입니다</span>
      <textarea aria-label="DeepL 지시문" value={text} onChange={(e) => onChange(e.target.value)} rows={4} autoFocus={autoFocus}
        placeholder="예: 모바일 RPG 게임 용어로 짧고 간결하게 번역한다."
        className={`${inputClass} resize-y text-[13px] leading-relaxed`} />
      {problem && <span role="alert" className="text-orange">{problem}</span>}
    </label>
  )
}
