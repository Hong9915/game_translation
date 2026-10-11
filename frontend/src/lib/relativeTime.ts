/** 변경 내역 항목의 시각. 서버 시계와 조금 어긋나 미래로 보여도 "방금". 하루가 넘으면 날짜·시각(브라우저 로컬). */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const t = new Date(iso)
  const sec = Math.floor((now.getTime() - t.getTime()) / 1000)
  if (sec < 60) return '방금'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}분 전`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}시간 전`
  const hh = String(t.getHours()).padStart(2, '0'), mm = String(t.getMinutes()).padStart(2, '0')
  return `${t.getMonth() + 1}/${t.getDate()} ${hh}:${mm}`
}
