/** 받침 있는 말 뒤엔 '으로', 받침이 없거나 ㄹ 받침이면 '로'. 한글로 끝나지 않으면 '로'. 서버 GlossaryService.withRo와 같다. */
export function ro(word: string): '로' | '으로' {
  const w = word.trim()
  const c = w.charCodeAt(w.length - 1)
  if (Number.isNaN(c) || c < 0xac00 || c > 0xd7a3) return '로'
  const jong = (c - 0xac00) % 28
  return jong === 0 || jong === 8 ? '로' : '으로'
}

/**
 * text 안의 oldKo를 ko로 바꾼다. 서버 GlossaryApplier.replacePartial과 같다 — ko가 oldKo를 품으면("트랩 장치"/"트랩")
 * ko로 먼저 쪼개 이미 ko인 자리는 그대로 두고 남은 조각에서만 바꾼다.
 */
export function replacePartial(text: string, oldKo: string, ko: string): string {
  if (!ko.includes(oldKo)) return text.split(oldKo).join(ko)
  return text.split(ko).map((piece) => piece.split(oldKo).join(ko)).join(ko)
}
