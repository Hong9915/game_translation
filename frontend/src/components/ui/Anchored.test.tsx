import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Anchored } from './Anchored'

// jsdom은 크기를 재지 않으므로 창 크기를 흉내 낸다
const size = (width: number, height: number) =>
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width, height, top: 0, left: 0, right: width, bottom: height, x: 0, y: 0, toJSON() {} })

describe('Anchored', () => {
  afterEach(() => vi.restoreAllMocks())

  it('화면 안이면 부른 자리에 그대로 둔다', () => {
    size(200, 100)
    Object.assign(window, { innerWidth: 1000, innerHeight: 800 })
    render(<Anchored x={300} y={200} data-testid="box">내용</Anchored>)
    expect(screen.getByTestId('box')).toHaveStyle({ left: '300px', top: '200px' })
  })

  it('오른쪽·아래로 넘치면 여백 8px을 두고 안쪽으로 밀어 넣는다', () => {
    size(200, 100)
    Object.assign(window, { innerWidth: 1000, innerHeight: 800 })
    render(<Anchored x={950} y={780} data-testid="box">내용</Anchored>)
    expect(screen.getByTestId('box')).toHaveStyle({ left: '792px', top: '692px' })
  })

  it('ref를 밖으로도 넘긴다 — 바깥 클릭 감지에 쓴다', () => {
    size(10, 10)
    const ref = { current: null as HTMLDivElement | null }
    render(<Anchored ref={ref} x={0} y={0}>내용</Anchored>)
    expect(ref.current).toBeInstanceOf(HTMLDivElement)
  })
})
