import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, dispositionName } from '../../api/client'
import { GameZipExportDialog } from './GameZipExportDialog'

describe('GameZipExportDialog', () => {
  afterEach(() => vi.restoreAllMocks())

  it('파일마다 차례로 덮어쓰기로 내보내고, 진행률을 보이며, 실패한 파일은 건너뛰고 알린다', async () => {
    let release: () => void = () => {}
    const spy = vi.spyOn(api, 'exportFile')
      .mockImplementationOnce(() => new Promise((res) => { release = () => res({ blob: new Blob([new Uint8Array([1])]), name: 'Skill_1차수정본.xlsx' }) }))
      .mockRejectedValueOnce(new ApiError(400, 'CSV 파일은 시트가 하나뿐입니다.'))
      .mockResolvedValueOnce({ blob: new Blob([new Uint8Array([2])]), name: null })
    const onDone = vi.fn()
    render(<GameZipExportDialog gameName="숲" ratio={1.3} onDone={onDone} onClose={vi.fn()} sheetName={null} onSheet={vi.fn()}
      files={[{ projectId: 1, fileName: 'Skill.xlsx' }, { projectId: 2, fileName: 'Text.csv' }, { projectId: 3, fileName: 'UI.xlsx' }]} />)
    // 시트를 하나 고르지 않았으면 "이 시트만"은 꺼져 있다
    expect(screen.getByRole('button', { name: /이 시트만/ })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /전체 파일 zip/ }))
    await userEvent.click(screen.getByRole('button', { name: 'zip 만들기' }))

    expect(await screen.findByText('1 / 3 파일')).toBeInTheDocument()
    expect(screen.getByText('Skill.xlsx')).toBeInTheDocument()
    release()

    expect(await screen.findByText(/파일 2개를 zip으로 받았습니다/)).toBeInTheDocument()
    expect(screen.getByText(/Text\.csv — CSV 파일은 시트가 하나뿐입니다/)).toBeInTheDocument()
    expect(spy).toHaveBeenCalledWith(1, { addFlagColumn: false, addHistorySheet: false, addGlossarySheet: false, ratio: 1.3, overwriteKo: true, addNoteColumn: false })
    const [zip, name, pids] = onDone.mock.calls[0]
    expect(name).toMatch(/^숲_\d{8}_\d{4}\.zip$/)
    expect(pids).toEqual([1, 3])
    // 이름을 못 받은 파일은 원래 파일 이름으로 들어간다
    const text = new TextDecoder().decode(await (zip as Blob).arrayBuffer())
    expect(text).toContain('Skill_1차수정본.xlsx')
    expect(text).toContain('UI.xlsx')
  })

  it('"이 시트만"을 고르면 시트 내보내기로 넘긴다', async () => {
    const onSheet = vi.fn()
    const spy = vi.spyOn(api, 'exportFile')
    render(<GameZipExportDialog gameName="숲" ratio={1.3} onDone={vi.fn()} onClose={vi.fn()} sheetName="Skill.xlsx › Skill" onSheet={onSheet}
      files={[{ projectId: 1, fileName: 'Skill.xlsx' }]} />)
    await userEvent.click(screen.getByRole('button', { name: /이 시트만/ }))
    expect(onSheet).toHaveBeenCalled()
    expect(spy).not.toHaveBeenCalled()
  })

  it('Content-Disposition에서 한글 파일 이름을 읽는다', () => {
    expect(dispositionName(`attachment; filename="=?UTF-8?Q?=ED=95=9C.xlsx?="; filename*=UTF-8''%ED%95%9C_1%EC%B0%A8.xlsx`)).toBe('한_1차.xlsx')
    expect(dispositionName('attachment; filename="a.xlsx"')).toBe('a.xlsx')
    expect(dispositionName(null)).toBeNull()
  })
})
