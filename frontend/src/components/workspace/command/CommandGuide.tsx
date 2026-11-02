/**
 * 명령으로 고치기 창의 안내(2026-10-11). 미리 보기 전·거절된 뒤 빈 자리에 할 수 있는 것(예시 명령)과 안 되는 것(대신 갈 곳)을 보인다.
 * 예시는 특정 게임 용어가 아닌 일반 예시 — 누르면 입력칸만 채우고 GPT는 부르지 않는다.
 */

const CAN: { what: string; example: string }[] = [
  { what: '찾아 바꾸기', example: "'보상'을 '리워드'로 바꾸고 조사도 맞춰줘" },
  { what: '원문 용어로 고르기', example: '金币 번역을 다 골드로 바꿔줘' },
  { what: '특정 행에서만', example: "'이벤트'가 든 행에서만 '기간'을 '일정'으로 바꿔줘" },
  { what: '띄어쓰기 · 기호', example: '- 앞뒤 띄어쓰기 없애줘' },
  { what: '숫자만 다른 문장', example: "'피해량이 N% 증가했습니다'를 'N% 피해량 증가'로 바꿔줘" },
]

const CANNOT: { what: string; instead: string }[] = [
  { what: '뜻을 보고 다듬기 · 다시 번역', instead: '셀 우클릭 → GPT 번역' },
  { what: '게임의 모든 파일에 한 번에', instead: '단어장' },
  { what: '새 태그 끼워 넣기 (\\n → <br>)', instead: '아직 안 됨 — 셀에서 직접' },
]

/** GPT가 부른 도구 이름 → 화면에 보일 이름. 모르는 이름은 그대로. */
const TOOL_LABEL: Record<string, string> = {
  find_rows: '행 찾기', try_ops: '시험해 보기', check_josa: '조사 검사', find_tag_mismatch: '태그 짝 검사', finish: '끝내기', cannot: '그만두기',
}
export const toolLabel = (tool: string) => TOOL_LABEL[tool] ?? tool

export function CommandGuide({ onPick }: { onPick: (example: string) => void }) {
  return (
    <section aria-label="명령으로 할 수 있는 것" className="grid gap-6 py-5 text-[13px] md:grid-cols-[3fr_2fr]">
      <div>
        <h3 className="text-xs font-semibold text-muted">할 수 있는 것 — 예시를 누르면 입력칸에 채워집니다</h3>
        <ul className="mt-2 space-y-1.5">
          {CAN.map((c) => (
            <li key={c.what} className="flex items-baseline gap-3">
              <span className="w-28 shrink-0 text-ink">{c.what}</span>
              <button type="button" onClick={() => onPick(c.example)}
                className="rounded-md border border-line bg-canvas px-2 py-0.5 text-left text-ink hover:border-primary hover:bg-primarySoft">
                {c.example}
              </button>
            </li>
          ))}
          <li className="flex items-baseline gap-3">
            <span className="w-28 shrink-0 text-ink">조사 · 태그 · 통일</span>
            <span className="text-muted">위 버튼 4개는 GPT 없이 바로 돌아갑니다</span>
          </li>
          <li className="flex items-baseline gap-3">
            <span className="w-28 shrink-0 text-ink">전체 길이 줄이기</span>
            <span className="text-muted">길이 기준을 넘는 행을 모두 GPT로 줄여 글자 수와 함께 비교합니다</span>
          </li>
        </ul>
      </div>
      <div>
        <h3 className="text-xs font-semibold text-muted">안 되는 것 → 대신 여기서</h3>
        <ul className="mt-2 space-y-1.5">
          {CANNOT.map((c) => (
            <li key={c.what} className="flex items-baseline gap-3">
              <span className="w-44 shrink-0 text-ink">{c.what}</span>
              <span className="text-muted">{c.instead}</span>
            </li>
          ))}
        </ul>
      </div>
      <p className="text-xs text-muted md:col-span-2">
        확정한 행은 바꾸지 않습니다 · 이 시트만 바뀝니다 · 태그·자리표시자 안쪽은 그대로 둡니다 · 고친 행은 변경 내역에서 되돌릴 수 있습니다
      </p>
    </section>
  )
}
