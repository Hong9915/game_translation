import type { ColumnPair } from '../api/types'

/** Fix를 어디에 쓸지. NEW = 맨 끝 새 열, OVERWRITE = 기존 한국어 열에 덮어쓰기. */
export type FixMode = 'NEW' | 'OVERWRITE'
export interface RoleConfig { keyCol: string | null; pairs: ColumnPair[] }

export const ROLE_NONE = 'NONE'

/** 새 Fix 열 헤더 기본값. */
export function defaultFixHeader(index: number): string {
  return index === 0 ? 'KR_Fix' : `KR_Fix${index + 1}`
}

/** 이 열이 지금 맡고 있는 역할. 어느 칸에도 안 들어가 있으면 'NONE'. */
export function roleValue(cfg: RoleConfig, letter: string): string {
  if (cfg.keyCol === letter) return 'ID'
  for (let i = 0; i < cfg.pairs.length; i++) {
    const p = cfg.pairs[i]
    if (p.srcCol === letter) return `SRC:${i}`
    if (p.enCol === letter) return `EN:${i}`
    if (p.koCol === letter) return `KO:${i}`
    if (p.fixCol === letter) return `FIX:${i}`
  }
  return ROLE_NONE
}

/** 드롭다운 선택지. 쌍이 여럿이면 라벨에 번호를 붙인다. 수정 한국어는 최종 리뷰 버전일 때만. */
export function roleOptions(pairCount: number, finalReview = false): { value: string; label: string }[] {
  const out = [{ value: ROLE_NONE, label: '— 없음 —' }, { value: 'ID', label: 'ID' }]
  for (let i = 0; i < pairCount; i++) {
    const n = pairCount > 1 ? ` ${i + 1}` : ''
    out.push({ value: `SRC:${i}`, label: `간체 원문${n}` })
    out.push({ value: `EN:${i}`, label: `영어${n}` })
    out.push({ value: `KO:${i}`, label: `기존 한국어${n}` })
    if (finalReview) out.push({ value: `FIX:${i}`, label: `수정 한국어${n}` })
  }
  return out
}

/** 기존 한국어 열을 읽는 쌍이 하나라도 있나 — 덮어쓰기를 고를 수 있는 조건. */
export function canOverwrite(cfg: RoleConfig): boolean {
  return cfg.pairs.some((p) => p.koCol !== null)
}

/** koCol과 Fix 모드로 정해지는 열 쌍 모드. 읽을 기존 열이 없으면 덮어쓸 곳도 없으니 새 열이다. */
function koMode(koCol: string | null, fix: FixMode): ColumnPair['koColMode'] {
  if (koCol === null) return 'NEW'
  return fix === 'OVERWRITE' ? 'EXISTING' : 'EXISTING_TO_NEW'
}

/**
 * 한 쌍의 koCol을 바꾸고 모드·헤더를 맞춘다. 수정 한국어 열(fixCol)은 기존 한국어 열과 함께만 있다 —
 * 기존 열이 빠지면 같이 빠지고, 있으면 그 열에 쓰므로 새 열 이름이 없다.
 */
function withKo(p: ColumnPair, koCol: string | null, index: number, fix: FixMode): ColumnPair {
  const fixCol = koCol === null ? null : (p.fixCol ?? null)
  if (fixCol) return { ...p, koCol, fixCol, koColMode: 'EXISTING_TO_NEW', koHeader: null }
  const mode = koMode(koCol, fix)
  return { ...p, koCol, fixCol: null, koColMode: mode, koHeader: mode === 'EXISTING' ? null : (p.koHeader ?? defaultFixHeader(index)) }
}

/** 이 열에 역할을 준다. 한 역할은 한 열만 가질 수 있으므로 예전 주인의 칸은 비운다. */
export function applyRole(cfg: RoleConfig, letter: string, value: string, fix: FixMode): RoleConfig {
  // 먼저 이 열이 갖고 있던 역할을 모두 비운다
  let keyCol = cfg.keyCol === letter ? null : cfg.keyCol
  let pairs = cfg.pairs.map((p, i) => {
    let next = p
    if (next.srcCol === letter) next = { ...next, srcCol: '' }
    if (next.enCol === letter) next = { ...next, enCol: null }
    if (next.koCol === letter) next = withKo(next, null, i, fix)
    if (next.fixCol === letter) next = withKo({ ...next, fixCol: null }, next.koCol, i, fix)
    return next
  })
  if (value === ROLE_NONE) return { keyCol, pairs }
  if (value === 'ID') return { keyCol: letter, pairs }

  const [kind, idxText] = value.split(':')
  const idx = Number(idxText)
  pairs = pairs.map((p, i) => {
    if (i !== idx) return p
    if (kind === 'SRC') return { ...p, srcCol: letter }
    if (kind === 'EN') return { ...p, enCol: letter }
    // 기존 한국어 열을 아직 안 골랐으면 수정 열만 잡아 둔다(정규화 버튼이 기존 열을 고르라고 막는다)
    if (kind === 'FIX') return p.koCol === null ? { ...p, fixCol: letter } : withKo({ ...p, fixCol: letter }, p.koCol, i, fix)
    return withKo(p, letter, i, fix)
  })
  return { keyCol, pairs }
}

/** Fix 모드를 바꾼다 — 기존 한국어를 읽는 쌍만 영향을 받는다. */
export function applyFixMode(cfg: RoleConfig, fix: FixMode): RoleConfig {
  return { ...cfg, pairs: cfg.pairs.map((p, i) => withKo(p, p.koCol, i, fix)) }
}

/** 최종 리뷰 버전을 끄면 수정 한국어 열을 모두 비우고 Fix 모드대로 되돌린다. 켤 때는 바뀌는 게 없다. */
export function applyFinal(cfg: RoleConfig, on: boolean, fix: FixMode): RoleConfig {
  if (on) return cfg
  return { ...cfg, pairs: cfg.pairs.map((p, i) => withKo({ ...p, fixCol: null }, p.koCol, i, fix)) }
}
