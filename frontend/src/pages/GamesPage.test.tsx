import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { renderWithProviders } from '../test/render'
import { GamesPage } from './GamesPage'

const games = [
  { id: 3, name: '드래곤 레이드', projects: 2, terms: 120, memory: 2318, lastUploadedAt: '2026-09-15T09:00:00Z', createdAt: '2026-09-01T00:00:00Z' },
  { id: 4, name: '스타 아레나', projects: 0, terms: 0, memory: 0, lastUploadedAt: null, createdAt: '2026-09-18T00:00:00Z' },
]

describe('GamesPage', () => {
  it('lists games with their file and memory counts and opens one', async () => {
    mockFetch([{ path: '/games', body: games }])
    renderWithProviders(<GamesPage />)
    const card = await screen.findByRole('link', { name: /드래곤 레이드/ })
    expect(card).toHaveTextContent('파일 2')
    expect(card).toHaveTextContent('메모리 2,318')
    expect(card).not.toHaveTextContent('용어')
    expect(screen.getByRole('link', { name: /스타 아레나/ })).toHaveTextContent('아직 파일 없음')
    await userEvent.click(card)
    expect(screen.getByTestId('location')).toHaveTextContent('/games/3')
  })

  it('creates a game and goes straight into it', async () => {
    const { calls } = mockFetch([
      { path: '/games', body: [] },
      { method: 'POST', path: '/games', body: { id: 9, name: '새 게임', projects: 0, terms: 0, memory: 0, lastUploadedAt: null, createdAt: '2026-09-21T00:00:00Z' } },
    ])
    renderWithProviders(<GamesPage />)
    expect(await screen.findByText('아직 게임이 없습니다. 첫 게임을 만들어 파일을 올리세요.')).toBeInTheDocument()
    const button = screen.getByRole('button', { name: '게임 만들기' })
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('새 게임 이름'), '새 게임{enter}')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/games/9'))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ name: '새 게임' })
  })

  it('shows the server message when the name already exists', async () => {
    mockFetch([
      { path: '/games', body: games },
      { method: 'POST', path: '/games', status: 400, body: { message: '이미 있는 게임 이름입니다: 드래곤 레이드' } },
    ])
    renderWithProviders(<GamesPage />)
    await screen.findByRole('link', { name: /드래곤 레이드/ })
    await userEvent.type(screen.getByLabelText('새 게임 이름'), '드래곤 레이드')
    await userEvent.click(screen.getByRole('button', { name: '게임 만들기' }))
    expect(await screen.findByText('이미 있는 게임 이름입니다: 드래곤 레이드')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/')
  })

  it('shows the accumulated GPT cost above the list', async () => {
    mockFetch([
      { path: '/games', body: [] },
      { path: '/usage', body: { calls: 206, promptTokens: 170500, completionTokens: 369400, reasoningTokens: 0, costUsd: 3.48, priced: true, byStage: [] } },
    ])
    renderWithProviders(<GamesPage />)
    expect(await screen.findByText(/누적 GPT 비용 \$3\.48/)).toBeInTheDocument()
    expect(screen.getByText(/206회/)).toBeInTheDocument()
  })

  it('falls back to token counts when prices are not configured', async () => {
    mockFetch([
      { path: '/games', body: [] },
      { path: '/usage', body: { calls: 12, promptTokens: 1000, completionTokens: 2000, reasoningTokens: 0, costUsd: 0, priced: false, byStage: [] } },
    ])
    renderWithProviders(<GamesPage />)
    expect(await screen.findByText(/토큰 3,000/)).toBeInTheDocument()
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument()
  })

  it('stays quiet when the usage request fails', async () => {
    mockFetch([{ path: '/games', body: [] }, { path: '/usage', status: 500, body: { message: 'nope' } }])
    renderWithProviders(<GamesPage />)
    expect(await screen.findByRole('heading', { name: '게임 선택' })).toBeInTheDocument()
    expect(screen.queryByText(/누적 GPT 비용/)).not.toBeInTheDocument()
  })
})
