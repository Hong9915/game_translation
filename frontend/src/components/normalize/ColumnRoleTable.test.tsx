import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ColumnPair } from '../../api/types'
import { ColumnRoleTable } from './ColumnRoleTable'

const headers = [
  { letter: 'A', text: 'SkillID' },
  { letter: 'B', text: 'Desc_CN' },
  { letter: 'C', text: 'Desc_EN' },
  { letter: 'D', text: 'Desc_KR' },
]
const rows = [['SKILL_1', '攻击力提升15%', 'ATK +15%', '공격력 증가 15%']]
const pairs: ColumnPair[] = [{ srcCol: 'B', enCol: 'C', koColMode: 'EXISTING_TO_NEW', koCol: 'D', koHeader: 'KR_Fix' }]
const config = { keyCol: 'A', pairs }

describe('ColumnRoleTable', () => {
  it('shows a role dropdown per column with the current role selected', () => {
    render(<ColumnRoleTable headers={headers} rows={rows} config={config} fixHeader="KR_Fix" onRole={vi.fn()} />)
    expect(screen.getByRole('combobox', { name: 'A열 역할' })).toHaveValue('ID')
    expect(screen.getByRole('combobox', { name: 'B열 역할' })).toHaveValue('SRC:0')
    expect(screen.getByRole('combobox', { name: 'C열 역할' })).toHaveValue('EN:0')
    expect(screen.getByRole('combobox', { name: 'D열 역할' })).toHaveValue('KO:0')
  })

  it('renders the header text and the preview rows', () => {
    render(<ColumnRoleTable headers={headers} rows={rows} config={config} fixHeader="KR_Fix" onRole={vi.fn()} />)
    expect(screen.getByText('Desc_CN')).toBeInTheDocument()
    expect(screen.getByText('攻击力提升15%')).toBeInTheDocument()
  })

  it('shows the new fix column with its name, and hides it when overwriting', () => {
    const { rerender } = render(<ColumnRoleTable headers={headers} rows={rows} config={config} fixHeader="KR_Fix" onRole={vi.fn()} />)
    expect(screen.getByText('KR_Fix')).toBeInTheDocument()
    expect(screen.getByText('(기존 한국어 복사)')).toBeInTheDocument()
    rerender(<ColumnRoleTable headers={headers} rows={rows} config={config} fixHeader={null} onRole={vi.fn()} />)
    expect(screen.queryByText('KR_Fix')).not.toBeInTheDocument()
  })

  it('reports a role change with the column letter and the chosen value', async () => {
    const onRole = vi.fn()
    render(<ColumnRoleTable headers={headers} rows={rows} config={config} fixHeader="KR_Fix" onRole={onRole} />)
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'C열 역할' }), 'NONE')
    expect(onRole).toHaveBeenCalledWith('C', 'NONE')
  })
})
