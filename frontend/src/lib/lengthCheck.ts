// 백엔드 com.gametrans.text.LengthCheck / PlaceholderCheck.TOKEN과 같은 규칙. 화면에 보이는 행만 계산하므로 서버 값이 필요 없다.
const TOKEN = /\{\d+\}|%[sd]|<\/?[A-Za-z][^<>]*>/g

export function lengthCount(s: string | null | undefined): number {
  if (!s) return 0
  const stripped = s.replace(TOKEN, '').replace(/\s+/g, '')
  return Array.from(stripped).length
}

/** 한국어가 원문 × ratio보다 길면 true. 원문이 비면(자리표시자뿐이면) 검사하지 않는다. */
export function lengthFlagged(src: string, ko: string | null | undefined, ratio: number): boolean {
  const n = lengthCount(src)
  if (n === 0) return false
  return lengthCount(ko) > n * ratio
}
