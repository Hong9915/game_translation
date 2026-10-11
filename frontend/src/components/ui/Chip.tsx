import type { ReactNode } from 'react'

type Tone = 'purple' | 'orange' | 'green' | 'gray' | 'blue'
const tone: Record<Tone, string> = {
  purple: 'bg-purpleSoft text-purple', orange: 'bg-orangeSoft text-orange', green: 'bg-greenSoft text-green',
  gray: 'bg-lineSoft text-muted', blue: 'bg-primarySoft text-primary',
}

export function Chip({ children, color = 'gray' }: { children: ReactNode; color?: Tone }) {
  return <span className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ${tone[color]}`}>{children}</span>
}
