import { useLayoutEffect, useState, type RefObject } from 'react'

const MARGIN = 8

/**
 * fixed로 뜨는 메뉴·작은 창을 (x, y)에 두되, 화면 밖으로 나가면 안쪽으로 밀어 넣는다.
 * 표 아래쪽·오른쪽에서 열어도 잘리지 않게. deps가 바뀌면(내용이 바뀌어 크기가 달라지면) 다시 맞춘다.
 */
export function useClampToViewport(ref: RefObject<HTMLElement | null>, x: number, y: number, deps: unknown[] = []) {
  const [pos, setPos] = useState({ top: y, left: x })
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const { width, height } = el.getBoundingClientRect()
    setPos({
      left: Math.max(MARGIN, Math.min(x, window.innerWidth - width - MARGIN)),
      top: Math.max(MARGIN, Math.min(y, window.innerHeight - height - MARGIN)),
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [x, y, ...deps])
  return pos
}
