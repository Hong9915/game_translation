const EXTENSIONS = ['xlsx', 'xlsm', 'xls', 'csv', 'tsv']
export const ACCEPT = EXTENSIONS.map((e) => `.${e}`).join(',')
export const MAX_BYTES = 50 * 1024 * 1024

/** 올릴 수 있는 파일인지: 지원 확장자이고 엑셀 잠금 파일(~$)이나 숨김 파일이 아니어야 한다. */
export function isUploadable(name: string): boolean {
  if (name.startsWith('~$') || name.startsWith('.')) return false
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
  return EXTENSIONS.includes(ext)
}

export function splitUploadable(files: File[]): { accepted: File[]; skipped: number } {
  const accepted = files.filter((f) => isUploadable(f.name))
  return { accepted, skipped: files.length - accepted.length }
}

/** 브라우저의 FileSystemEntry 최소 형태. 타입 정의가 브라우저마다 달라 직접 적는다. */
interface Entry { isFile: boolean; isDirectory: boolean; name: string }
interface FileEntry extends Entry { file: (ok: (f: File) => void, fail?: (e: unknown) => void) => void }
interface DirEntry extends Entry { createReader: () => { readEntries: (ok: (list: Entry[]) => void, fail?: (e: unknown) => void) => void } }

async function walk(entry: Entry, out: File[]): Promise<void> {
  if (entry.isFile) {
    out.push(await new Promise<File>((ok, fail) => (entry as FileEntry).file(ok, fail)))
    return
  }
  if (!entry.isDirectory) return
  const reader = (entry as DirEntry).createReader()
  // readEntries는 한 번에 일부만 주므로 빈 배열이 올 때까지 반복한다
  for (;;) {
    const batch = await new Promise<Entry[]>((ok, fail) => reader.readEntries(ok, fail))
    if (batch.length === 0) break
    for (const e of batch) await walk(e, out)
  }
}

/** 끌어다 놓은 것에서 파일을 모두 꺼낸다. 폴더면 하위 폴더까지 훑는다. */
export async function filesFromDataTransfer(dt: DataTransfer): Promise<File[]> {
  const items = Array.from(dt.items ?? [])
  const entries = items.map((it) => (typeof it.webkitGetAsEntry === 'function' ? (it.webkitGetAsEntry() as Entry | null) : null))
  if (entries.length === 0 || entries.some((e) => e === null)) return Array.from(dt.files)
  const out: File[] = []
  for (const e of entries) await walk(e as Entry, out)
  return out
}
