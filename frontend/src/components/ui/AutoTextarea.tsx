import { useLayoutEffect, useRef, type TextareaHTMLAttributes } from 'react'

/** 내용 길이에 맞춰 높이가 자동으로 늘어나는 textarea. 긴 번역문을 한 번에 다 보이게 한다. */
export function AutoTextarea({ className = '', value, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const ref = useRef<HTMLTextAreaElement>(null)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [value])
  return <textarea ref={ref} rows={1} value={value} className={`resize-none overflow-hidden ${className}`} {...rest} />
}
