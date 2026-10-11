import { describe, expect, it } from 'vitest'
import { filesFromDataTransfer, splitUploadable } from './files'

const f = (name: string) => new File(['x'], name)

describe('splitUploadable', () => {
  it('keeps spreadsheet files and skips lock, hidden and unsupported files', () => {
    const { accepted, skipped } = splitUploadable([
      f('ItemText.xlsx'), f('skills.CSV'), f('macro.xlsm'), f('old.xls'), f('tab.tsv'),
      f('~$ItemText.xlsx'), f('.DS_Store'), f('readme.txt'), f('image.png'),
    ])
    expect(accepted.map((x) => x.name)).toEqual(['ItemText.xlsx', 'skills.CSV', 'macro.xlsm', 'old.xls', 'tab.tsv'])
    expect(skipped).toBe(4)
  })
})

/** 브라우저의 FileSystemEntry를 흉내 낸다: 폴더는 children, 파일은 file. */
type FakeEntry = { isFile: boolean; isDirectory: boolean; name: string; file?: File; children?: FakeEntry[] }
const dir = (name: string, children: FakeEntry[]): FakeEntry => ({ isFile: false, isDirectory: true, name, children })
const fileEntry = (name: string): FakeEntry => ({ isFile: true, isDirectory: false, name, file: f(name) })
function toEntry(e: FakeEntry): unknown {
  if (e.isFile) return { isFile: true, isDirectory: false, name: e.name, file: (ok: (f: File) => void) => ok(e.file as File) }
  let served = false
  return {
    isFile: false, isDirectory: true, name: e.name,
    createReader: () => ({
      // 실제 브라우저처럼 첫 호출에 일부, 다음 호출에 빈 배열을 준다
      readEntries: (ok: (list: unknown[]) => void) => { const out = served ? [] : (e.children ?? []).map(toEntry); served = true; ok(out) },
    }),
  }
}

describe('filesFromDataTransfer', () => {
  it('walks dropped folders recursively and returns every file inside', async () => {
    const tree = dir('game', [fileEntry('a.xlsx'), dir('sub', [fileEntry('b.csv'), dir('deep', [fileEntry('c.xlsx')])]), fileEntry('notes.txt')])
    const items = [{ webkitGetAsEntry: () => toEntry(tree) }, { webkitGetAsEntry: () => toEntry(fileEntry('top.xlsx')) }]
    const dt = { items, files: [] } as unknown as DataTransfer
    const files = await filesFromDataTransfer(dt)
    expect(files.map((x) => x.name).sort()).toEqual(['a.xlsx', 'b.csv', 'c.xlsx', 'notes.txt', 'top.xlsx'])
  })

  it('falls back to dataTransfer.files when entries are unavailable', async () => {
    const dt = { items: [{}], files: [f('plain.xlsx')] } as unknown as DataTransfer
    const files = await filesFromDataTransfer(dt)
    expect(files.map((x) => x.name)).toEqual(['plain.xlsx'])
  })
})
