// 게임 전체 zip 내보내기(2026-10-28). xlsx는 이미 압축된 파일이라 다시 누르지 않고 저장(store)만 한다 — 라이브러리 없이 충분하다.
export interface ZipEntry { name: string; data: Uint8Array }

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** 같은 이름이 둘이면 뒤에 (2), (3)… 을 붙인다 — zip 안에서 하나가 다른 하나를 덮지 않게. */
export function uniqueNames(names: string[]): string[] {
  const seen = new Set<string>()
  return names.map((n) => {
    let out = n
    const dot = n.lastIndexOf('.')
    const [base, ext] = dot > 0 ? [n.slice(0, dot), n.slice(dot)] : [n, '']
    for (let i = 2; seen.has(out); i++) out = `${base} (${i})${ext}`
    seen.add(out)
    return out
  })
}

/** 파일 이름은 UTF-8(플래그 비트 11)로 적어 한글 이름이 윈도·맥 모두에서 깨지지 않게 한다. */
export function zipStore(entries: ZipEntry[], now: Date = new Date()): Blob {
  const enc = new TextEncoder()
  const time = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1)
  const date = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()
  const parts: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const e of entries) {
    const name = enc.encode(e.name)
    const crc = crc32(e.data)
    const local = new DataView(new ArrayBuffer(30))
    local.setUint32(0, 0x04034b50, true)
    local.setUint16(4, 20, true)
    local.setUint16(6, 0x0800, true)
    local.setUint16(8, 0, true)
    local.setUint16(10, time, true)
    local.setUint16(12, date, true)
    local.setUint32(14, crc, true)
    local.setUint32(18, e.data.length, true)
    local.setUint32(22, e.data.length, true)
    local.setUint16(26, name.length, true)
    local.setUint16(28, 0, true)
    parts.push(new Uint8Array(local.buffer), name, e.data)

    const cd = new DataView(new ArrayBuffer(46))
    cd.setUint32(0, 0x02014b50, true)
    cd.setUint16(4, 20, true)
    cd.setUint16(6, 20, true)
    cd.setUint16(8, 0x0800, true)
    cd.setUint16(10, 0, true)
    cd.setUint16(12, time, true)
    cd.setUint16(14, date, true)
    cd.setUint32(16, crc, true)
    cd.setUint32(20, e.data.length, true)
    cd.setUint32(24, e.data.length, true)
    cd.setUint16(28, name.length, true)
    cd.setUint32(42, offset, true)
    central.push(new Uint8Array(cd.buffer), name)
    offset += 30 + name.length + e.data.length
  }
  const cdSize = central.reduce((s, p) => s + p.length, 0)
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true)
  end.setUint16(8, entries.length, true)
  end.setUint16(10, entries.length, true)
  end.setUint32(12, cdSize, true)
  end.setUint32(16, offset, true)
  return new Blob([...parts, ...central, new Uint8Array(end.buffer)] as BlobPart[], { type: 'application/zip' })
}
