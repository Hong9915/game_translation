import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ExportModeDialog } from './ExportModeDialog'

describe('ExportModeDialog', () => {
  it('새 열 / 기존 열에 덮어쓰기를 고르고, Esc로 닫는다', async () => {
    const onPick = vi.fn(), onClose = vi.fn()
    render(<ExportModeDialog fileName="Skill_v2.xlsx" canOverwrite finalMarked={null} finalFileName={null} onPick={onPick} onClose={onClose} />)
    expect(screen.getByText(/Skill_v2\.xlsx/)).toBeInTheDocument()
    expect(screen.queryByText('최종 수정분만')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /기존 한국어 열에 덮어쓰기/ }))
    expect(onPick).toHaveBeenLastCalledWith({ overwriteKo: true, finalOnly: false })
    await userEvent.click(screen.getByRole('button', { name: /새 열로 추가/ }))
    expect(onPick).toHaveBeenLastCalledWith({ overwriteKo: false, finalOnly: false })
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('최종 리뷰 시트가 있으면 최종 수정분만을 고를 수 있고, 받을 파일 이름이 바뀐다', async () => {
    const onPick = vi.fn()
    render(<ExportModeDialog fileName="UI_1차수정본.xlsx" canOverwrite finalMarked={12} finalFileName="UI_최종수정분_1차.xlsx" onPick={onPick} onClose={vi.fn()} />)
    await userEvent.click(screen.getByRole('radio', { name: /최종 수정분만/ }))
    expect(screen.getByText(/UI_최종수정분_1차\.xlsx/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /새 열로 추가/ }))
    expect(onPick).toHaveBeenLastCalledWith({ overwriteKo: false, finalOnly: true })
  })

  it('표시한 행이 없으면 최종 수정분만은 못 고른다', () => {
    render(<ExportModeDialog fileName="UI.xlsx" canOverwrite={false} finalMarked={0} finalFileName="UI_최종수정분_1차.xlsx" onPick={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('radio', { name: /최종 수정분만/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '내보내기' })).toBeInTheDocument()
  })
})
