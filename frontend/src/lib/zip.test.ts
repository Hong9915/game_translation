import { describe, expect, it } from 'vitest'
import { crc32, uniqueNames, zipStore } from './zip'

describe('zip', () => {
  it('crc32는 표준 값과 같다', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926)
  })

  it('같은 이름에 번호를 붙인다', () => {
    expect(uniqueNames(['a.xlsx', 'b.xlsx', 'a.xlsx', 'a.xlsx'])).toEqual(['a.xlsx', 'b.xlsx', 'a (2).xlsx', 'a (3).xlsx'])
  })

  it('저장 방식 zip의 머리·끝 레코드를 쓴다', async () => {
    const blob = zipStore([{ name: '한글.xlsx', data: new Uint8Array([1, 2, 3]) }, { name: 'b.csv', data: new Uint8Array([4]) }])
    const buf = new Uint8Array(await blob.arrayBuffer())
    const dv = new DataView(buf.buffer)
    expect(dv.getUint32(0, true)).toBe(0x04034b50)
    const end = buf.length - 22
    expect(dv.getUint32(end, true)).toBe(0x06054b50)
    expect(dv.getUint16(end + 10, true)).toBe(2)
    const cdOffset = dv.getUint32(end + 16, true)
    expect(dv.getUint32(cdOffset, true)).toBe(0x02014b50)
    // 첫 파일 내용이 이름 바로 뒤에 그대로 있다
    const nameLen = dv.getUint16(26, true)
    expect([...buf.slice(30 + nameLen, 33 + nameLen)]).toEqual([1, 2, 3])
  })
})
