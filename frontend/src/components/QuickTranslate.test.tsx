import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { QuickTranslate } from './QuickTranslate'

const result = { ko: '서리속삭임이 파티에 합류했습니다', source: 'LLM', glossary: { 霜语者: '서리속삭임' }, references: [{ cn: '霜语者离开了队伍', ko: '서리속삭임이 파티를 떠났습니다' }], termMiss: [], placeholderOk: true }

describe('QuickTranslate', () => {
  it('translates one sentence for this game and shows the glossary that was applied', async () => {
    const { calls } = mockFetch([{ method: 'POST', path: '/games/3/quick-translate', body: result }])
    renderWithProviders(<QuickTranslate gameId={3} />)
    const button = screen.getByRole('button', { name: '번역' })
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('번역할 문장'), '霜语者加入了队伍')
    await userEvent.click(button)
    expect(await screen.findByLabelText('번역 결과')).toHaveTextContent('서리속삭임이 파티에 합류했습니다')
    expect(screen.getByText('霜语者 → 서리속삭임')).toBeInTheDocument()
    expect(screen.getByText('참고한 기존 번역 1개')).toBeInTheDocument()
    expect(calls[0].body).toMatchObject({ text: '霜语者加入了队伍', basis: 'CN_ONLY' })
  })

  it('warns about a missing term or broken placeholders and surfaces API errors', async () => {
    mockFetch([{ method: 'POST', path: '/games/3/quick-translate', body: { ...result, source: 'MEMORY', termMiss: ['霜语者→서리속삭임'], placeholderOk: false } }])
    renderWithProviders(<QuickTranslate gameId={3} />)
    await userEvent.type(screen.getByLabelText('번역할 문장'), '霜语者')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText('용어 누락: 霜语者→서리속삭임')).toBeInTheDocument()
    expect(screen.getByText(/자리표시자·태그가 원문과 다릅니다/)).toBeInTheDocument()
    expect(screen.getByText('번역 메모리')).toBeInTheDocument()

    mockFetch([{ method: 'POST', path: '/games/3/quick-translate', status: 409, body: { message: 'GPT가 번역을 돌려주지 않았습니다. 다시 시도하세요.' } }])
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText('GPT가 번역을 돌려주지 않았습니다. 다시 시도하세요.')).toBeInTheDocument()
  })

  it('says the answer came from the DeepL cache', async () => {
    mockFetch([{ method: 'POST', path: '/games/3/quick-translate', body: { ...result, ko: '서리속삭임이 합류했다', source: 'CACHE', glossary: {}, references: [], problem: null } }])
    renderWithProviders(<QuickTranslate gameId={3} />)
    await userEvent.type(screen.getByRole('textbox', { name: '번역할 문장' }), '霜语者加入了队伍')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText('DeepL (캐시 · 다시 사지 않음)')).toBeInTheDocument()
  })

  it('warns when DeepL left Chinese in the answer', async () => {
    mockFetch([{ method: 'POST', path: '/games/3/quick-translate', body: { ...result, ko: '扇형 범위', source: 'DEEPL', glossary: {}, references: [], problem: 'HAN' } }])
    renderWithProviders(<QuickTranslate gameId={3} />)
    await userEvent.type(screen.getByRole('textbox', { name: '번역할 문장' }), '扇形范围')
    await userEvent.click(screen.getByRole('button', { name: '번역' }))
    expect(await screen.findByText(/중국어가 남았습니다/)).toBeInTheDocument()
  })
})
