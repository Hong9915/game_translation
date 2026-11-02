import type { HeaderCell } from '../../api/types'
import { ROLE_NONE, roleOptions, roleValue, type RoleConfig } from '../../lib/columnRoles'

export interface ColumnRoleTableProps {
  headers: HeaderCell[]
  rows: string[][]
  config: RoleConfig
  fixHeader: string | null
  /** 최종 리뷰 버전이면 "수정 한국어" 역할도 고를 수 있다. */
  finalReview?: boolean
  onRole: (letter: string, value: string) => void
}

/** 역할별 드롭다운 색. 한눈에 어느 열이 무엇인지 보이게 한다. */
function roleClass(value: string): string {
  if (value === 'ID') return 'border-muted bg-tableHead text-ink'
  if (value.startsWith('SRC')) return 'border-ink bg-ink text-white'
  if (value.startsWith('EN')) return 'border-line bg-white text-ink'
  if (value.startsWith('KO')) return 'border-green bg-greenSoft text-green'
  if (value.startsWith('FIX')) return 'border-orange bg-orangeSoft text-ink'
  return 'border-line bg-white text-muted'
}

/** 엑셀 미리보기 위에 열마다 역할 드롭다운을 붙인 표. 상태는 부모(NormalizePage)가 들고 있다. */
export function ColumnRoleTable({ headers, rows, config, fixHeader, finalReview = false, onRole }: ColumnRoleTableProps) {
  const options = roleOptions(config.pairs.length, finalReview)
  const cell = 'max-w-[220px] overflow-hidden text-ellipsis whitespace-nowrap border border-line px-1.5 py-1'
  return (
    <div className="overflow-auto">
      <table className="w-max border-collapse text-[13px]">
        <thead>
          <tr>
            <th className="border border-line bg-tableHead px-1.5 py-0.5 text-[11px] text-muted" />
            {headers.map((h) => (
              <th key={h.letter} className="border border-line bg-tableHead px-1.5 py-0.5 text-center text-[11px] font-normal text-muted">{h.letter}</th>
            ))}
            {fixHeader && <th className="border border-dashed border-[#f0c36d] bg-[#fffbea] px-1.5 py-0.5 text-center text-[11px] font-normal text-[#7c5200]">새 열</th>}
          </tr>
          <tr>
            <th className="border border-line bg-white px-1 py-1" />
            {headers.map((h) => {
              const value = roleValue(config, h.letter)
              return (
                <th key={h.letter} className="border border-line bg-white px-1 py-1">
                  <select aria-label={`${h.letter}열 역할`} value={value} onChange={(e) => onRole(h.letter, e.target.value)}
                    className={`w-full rounded border px-1 py-0.5 text-xs ${roleClass(value)}`}>
                    {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </th>
              )
            })}
            {fixHeader && <th className="border border-dashed border-[#f0c36d] bg-[#fff7d6] px-1 py-1 text-xs text-[#7c5200]">수정 한국어 (Fix)</th>}
          </tr>
        </thead>
        <tbody>
          <tr className="font-semibold">
            <td className="border border-line bg-tableHead px-1.5 py-1 text-center text-[11px] text-muted">헤더</td>
            {headers.map((h) => <td key={h.letter} className={`${cell} bg-canvas`}>{h.text}</td>)}
            {fixHeader && <td className={`${cell} border-dashed border-[#f0c36d] bg-[#fffbea] text-[#7c5200]`}>{fixHeader}</td>}
          </tr>
          {rows.map((r, i) => (
            <tr key={i}>
              <td className="border border-line bg-tableHead px-1.5 py-1 text-center text-[11px] text-muted">{i + 1}</td>
              {headers.map((h, c) => <td key={h.letter} className={cell} title={r[c] ?? ''}>{r[c] ?? ''}</td>)}
              {fixHeader && <td className={`${cell} border-dashed border-[#f0c36d] bg-[#fffbea] text-muted`}>(기존 한국어 복사)</td>}
            </tr>
          ))}
        </tbody>
      </table>
      {headers.every((h) => roleValue(config, h.letter) === ROLE_NONE) && (
        <p className="mt-2 text-xs text-orange">열 역할을 하나도 지정하지 않았습니다. 최소한 간체 원문 열을 골라 주세요.</p>
      )}
    </div>
  )
}
