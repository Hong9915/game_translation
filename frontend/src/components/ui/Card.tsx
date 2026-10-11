import type { HTMLAttributes } from 'react'

export function Card({ className = '', ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`rounded-xl border border-line/70 bg-white shadow-[0_1px_2px_rgba(16,24,40,0.04)] ${className}`} {...rest} />
}
