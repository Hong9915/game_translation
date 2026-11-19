import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ProposalDetail, ProposalResult, ProposalRow } from '../../api/types'
import { mockFetch, type MockRoute } from '../../test/mockFetch'
import { renderWithProviders } from '../../test/render'
import { ProposalDialog } from './ProposalDialog'

const prow = (id: number, over: Partial<ProposalRow> = {}): ProposalRow => ({
  segmentId: id, projectId: 1, fileName: 'a.xlsx', sheet: 'Sheet1', rowNo: id, indexLabel: null,
  src: `原${id}`, fix: `고침${id}`, base: `고침${id}`, text: `새글${id}`, reason: `까닭${id}`, finalReview: false, lockedReason: null, srcMissing: false, ...over,
})
const detail = (over: Partial<ProposalDetail> = {}): ProposalDetail => ({
  id: 5, reviewerId: 1, gameId: 3, title: '다듬기', status: 'PENDING', createdAt: '2026-11-17T00:00:00Z', decidedAt: null,
  rows: [prow(1), prow(2, { lockedReason: '확정' }), prow(3)], result: null, termSrc: null, ...over,
})
const result: ProposalResult = { saved: 2, unchanged: 0, propagated: 1, excluded: [], skippedRows: [], finalMarked: 0, activityIds: [9] }

const setup = (routes: MockRoute[], position: [number, number] = [1, 1]) => {
  const m = mockFetch(routes)
  const props = { onSaved: vi.fn(), onDismissed: vi.fn(), onLater: vi.fn(), onGone: vi.fn() }
  renderWithProviders(<ProposalDialog proposalId={5} position={position} {...props} />)
  return { ...m, ...props }
}
const get = (d: ProposalDetail = detail()): MockRoute => ({ path: '/connector/proposals/5', body: d })
const posts = <T extends { method: string }>(calls: T[]) => calls.filter((c) => c.method !== 'GET')

afterEach(() => vi.unstubAllGlobals())

describe('ProposalDialog', () => {
  it('rendersRowsCheckedExceptLocked', async () => {
    setup([get()])
    expect(await screen.findByRole('heading', { name: 'Claude가 고칠 안 — 다듬기 (3행)' })).toBeInTheDocument()
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(3)
    expect(boxes[0]).toBeChecked()
    expect(boxes[1]).not.toBeChecked()
    expect(boxes[1]).toBeDisabled()
    expect(boxes[2]).toBeChecked()
    expect(screen.getByText('🔒 확정')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '체크한 2행 저장' })).toBeInTheDocument()
    expect(screen.getByText('#1')).toBeInTheDocument()
    expect(screen.getByText('原1')).toBeInTheDocument()
    expect(screen.getByText('고침1')).toBeInTheDocument()
    expect(screen.getByText('새글1')).toBeInTheDocument()
    expect(screen.getByText('이유: 까닭1')).toBeInTheDocument()
  })

  it('showsPositionWhenMany', async () => {
    setup([get()], [1, 3])
    await screen.findByRole('heading')
    expect(screen.getByText('1/3')).toBeInTheDocument()
  })

  it('hidesPositionWhenSingle', async () => {
    setup([get()], [1, 1])
    await screen.findByRole('heading')
    expect(screen.queryByText('1/1')).toBeNull()
  })

  it('uncheckedRowLeavesApply', async () => {
    const { calls, onSaved } = setup([get(), { method: 'POST', path: '/connector/proposals/5/apply', body: detail({ status: 'APPLIED', result }) }])
    await screen.findByRole('heading')
    await userEvent.click(screen.getAllByRole('checkbox')[0])
    await userEvent.click(screen.getByRole('button', { name: '체크한 1행 저장' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(result))
    const p = posts(calls)
    expect(p).toHaveLength(1)
    expect(p[0].url).toContain('/connector/proposals/5/apply')
    expect(p[0].body).toEqual({ segmentIds: [3] })
  })

  it('laterDoesNotCallServer', async () => {
    const { calls, onLater } = setup([get()])
    await screen.findByRole('heading')
    await userEvent.click(screen.getByRole('button', { name: '나중에' }))
    expect(onLater).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onLater).toHaveBeenCalledTimes(2)
    expect(posts(calls)).toHaveLength(0)
  })

  it('dismissCallsServer', async () => {
    const { calls, onDismissed } = setup([get(), { method: 'POST', path: '/connector/proposals/5/dismiss', body: detail({ status: 'DISMISSED' }) }])
    await screen.findByRole('heading')
    await userEvent.click(screen.getByRole('button', { name: '넣지 않음' }))
    await waitFor(() => expect(onDismissed).toHaveBeenCalled())
    expect(posts(calls).map((c) => c.url)).toEqual([expect.stringContaining('/connector/proposals/5/dismiss')])
  })

  it('conflictClosesQuietly', async () => {
    const { onGone, onSaved } = setup([get(), { method: 'POST', path: '/apply', status: 409, body: { message: '이미 처리된 제안입니다.', proposal: detail({ status: 'APPLIED' }) } }])
    await screen.findByRole('heading')
    await userEvent.click(screen.getByRole('button', { name: '체크한 2행 저장' }))
    await waitFor(() => expect(onGone).toHaveBeenCalledWith('이미 처리된 제안입니다.'))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('alreadyDecidedDetailGoesAway', async () => {
    const { onGone } = setup([get(detail({ status: 'APPLIED' }))])
    await waitFor(() => expect(onGone).toHaveBeenCalledWith('이미 처리된 제안입니다.'))
  })

  it('otherErrorShowsAlert', async () => {
    const { onGone, onSaved } = setup([get(), { method: 'POST', path: '/apply', status: 500, body: { message: '서버 오류' } }])
    await screen.findByRole('heading')
    await userEvent.click(screen.getByRole('button', { name: '체크한 2행 저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('서버 오류')
    expect(onGone).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '체크한 2행 저장' })).toBeEnabled()
  })

  it('saveButtonIgnoresDoubleClick', async () => {
    const { calls } = setup([get(), { method: 'POST', path: '/apply', body: detail({ status: 'APPLIED', result }) }])
    await screen.findByRole('heading')
    const btn = screen.getByRole('button', { name: '체크한 2행 저장' })
    fireEvent.click(btn)
    fireEvent.click(btn)
    await waitFor(() => expect(posts(calls).length).toBeGreaterThan(0))
    await new Promise((r) => setTimeout(r, 20))
    expect(posts(calls)).toHaveLength(1)
  })

  it('cmdEnterSaves', async () => {
    const { calls, onSaved } = setup([get(), { method: 'POST', path: '/apply', body: detail({ status: 'APPLIED', result }) }])
    await screen.findByRole('heading')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Enter', metaKey: true })
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(result))
    expect(posts(calls)).toHaveLength(1)
  })

  it('finalLaneNotice', async () => {
    const d = detail({ rows: [prow(1, { finalReview: true }), prow(3)] })
    setup([get(d)])
    await screen.findByRole('heading')
    const note = "최종 리뷰 시트라 저장하면 '최종 수정'이 켜집니다"
    expect(screen.getByText(note)).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('checkbox')[0])
    expect(screen.queryByText(note)).toBeNull()
  })

  it('notFoundDetailGoesAway', async () => {
    const { onGone } = setup([{ path: '/connector/proposals/5', status: 404, body: { message: '없음' } }])
    await waitFor(() => expect(onGone).toHaveBeenCalledWith('제안을 찾을 수 없습니다.'))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('otherDetailErrorShowsAlertAndLater', async () => {
    const { onLater, onGone } = setup([{ path: '/connector/proposals/5', status: 500, body: { message: '서버 오류' } }])
    expect(await screen.findByRole('alert')).toHaveTextContent('서버 오류')
    expect(onGone).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '나중에' }))
    expect(onLater).toHaveBeenCalledTimes(1)
  })

  it('keysWorkWhenFocusStartsOutsideDialog', async () => {
    const outerKey = vi.fn()
    const m = mockFetch([get(), { method: 'POST', path: '/apply', body: detail({ status: 'APPLIED', result }) }])
    const props = { onSaved: vi.fn(), onDismissed: vi.fn(), onLater: vi.fn(), onGone: vi.fn() }
    renderWithProviders(<><button onKeyDown={outerKey}>바깥</button><ProposalDialog proposalId={5} position={[1, 1]} {...props} /></>)
    const outer = screen.getByRole('button', { name: '바깥' })
    outer.focus()
    await screen.findByRole('heading')
    await waitFor(() => expect(screen.getByRole('dialog').firstElementChild).toHaveFocus())
    outer.focus()
    fireEvent.keyDown(outer, { key: 'Escape' })
    expect(props.onLater).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(outer, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(props.onSaved).toHaveBeenCalledWith(result))
    expect(posts(m.calls)).toHaveLength(1)
    expect(outerKey).not.toHaveBeenCalled()
  })

  it('restoresFocusOnUnmount', async () => {
    mockFetch([get()])
    const Host = () => {
      const [show, setShow] = useState(true)
      return show ? <ProposalDialog proposalId={5} position={[1, 1]} onSaved={vi.fn()} onDismissed={vi.fn()} onLater={() => setShow(false)} onGone={vi.fn()} /> : null
    }
    // 창이 뜨기 전에 포커스는 이미 바깥(그리드 칸 같은 곳)에 있다
    const outer = document.createElement('button')
    document.body.appendChild(outer)
    outer.focus()
    renderWithProviders(<Host />)
    await screen.findByRole('heading')
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(outer).toHaveFocus()
    outer.remove()
  })

  it('refetchesOnRemountSoLockFollowsFreshDetail', async () => {
    let current = detail()
    const m = mockFetch([{ path: '/connector/proposals/5', body: current }])
    m.fn.mockImplementation(async () => new Response(JSON.stringify(current), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const props = { onSaved: vi.fn(), onDismissed: vi.fn(), onLater: vi.fn(), onGone: vi.fn() }
    const view = renderWithProviders(<ProposalDialog proposalId={5} position={[1, 1]} {...props} />)
    await screen.findByRole('button', { name: '체크한 2행 저장' })
    view.unmount()
    current = detail({ rows: [prow(1, { lockedReason: '확정' }), prow(2, { lockedReason: '확정' }), prow(3)] })
    renderWithProviders(<ProposalDialog proposalId={5} position={[1, 1]} {...props} />)
    expect(await screen.findByRole('button', { name: '체크한 1행 저장' })).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')[0]).toBeDisabled()
    expect(screen.getAllByRole('checkbox')[0]).not.toBeChecked()
  })

  it('escDuringLoadIsNotSwallowed', async () => {
    const m = mockFetch([])
    m.fn.mockImplementation(() => new Promise<Response>(() => { /* 상세를 아직 못 읽는 중 */ }))
    const outer = vi.fn()
    window.addEventListener('keydown', outer)
    const props = { onSaved: vi.fn(), onDismissed: vi.fn(), onLater: vi.fn(), onGone: vi.fn() }
    renderWithProviders(<ProposalDialog proposalId={5} position={[1, 1]} {...props} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    window.removeEventListener('keydown', outer)
    expect(outer).toHaveBeenCalledTimes(1)
    expect(props.onLater).not.toHaveBeenCalled()
  })

  it('srcMissingRowsStartUncheckedWithLabel', async () => {
    const d = detail({ termSrc: '关卡', rows: [prow(1), prow(2), prow(3, { srcMissing: true })] })
    setup([get(d)])
    await screen.findByRole('heading')
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes[0]).toBeChecked()
    expect(boxes[1]).toBeChecked()
    expect(boxes[2]).not.toBeChecked()
    expect(boxes[2]).toBeEnabled()
    expect(screen.getByText('원문에 关卡 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '체크한 2행 저장' })).toBeInTheDocument()
  })

  it('srcMissingLabelWithoutTermSrc', async () => {
    setup([get(detail({ rows: [prow(1, { srcMissing: true })] }))])
    expect(await screen.findByText('원문에 용어 없음')).toBeInTheDocument()
  })

  it('turningOnSrcMissingRowSavesIt', async () => {
    const d = detail({ termSrc: '关卡', rows: [prow(1), prow(2), prow(3, { srcMissing: true })] })
    const { calls, onSaved } = setup([get(d), { method: 'POST', path: '/connector/proposals/5/apply', body: detail({ status: 'APPLIED', result }) }])
    await screen.findByRole('heading')
    await userEvent.click(screen.getAllByRole('checkbox')[2])
    await userEvent.click(screen.getByRole('button', { name: '체크한 3행 저장' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(posts(calls)[0].body).toEqual({ segmentIds: [1, 2, 3] })
  })
})
