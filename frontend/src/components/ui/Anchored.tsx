import { useRef, type HTMLAttributes, type Ref } from 'react'
import { useClampToViewport } from '../../hooks/useClampToViewport'

interface AnchoredProps extends HTMLAttributes<HTMLDivElement> {
  /** 부른 자리(화면 좌표). 창은 여기서 시작해 커진다(origin-top-left는 쓰는 곳의 className에서). */
  x: number
  y: number
  /** 내용이 바뀌어 크기가 달라지는 시점(후보 도착 등). 바뀌면 화면 안으로 다시 맞춘다. */
  deps?: unknown[]
  ref?: Ref<HTMLDivElement>
}

/** 화면 좌표 (x, y)에 fixed로 뜨되 화면 밖으로 나가지 않는 상자. 우클릭 메뉴·작은 창의 공통 껍데기. */
export function Anchored({ x, y, deps, ref, style, className = '', ...rest }: AnchoredProps) {
  const inner = useRef<HTMLDivElement | null>(null)
  const pos = useClampToViewport(inner, x, y, deps)
  const setRef = (el: HTMLDivElement | null) => {
    inner.current = el
    if (typeof ref === 'function') ref(el)
    else if (ref) ref.current = el
  }
  return <div ref={setRef} style={{ ...pos, ...style }} className={`fixed ${className}`} {...rest} />
}
