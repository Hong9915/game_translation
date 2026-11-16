/**
 * id를 size개씩 묶어 순서대로 하나씩 step에 넘긴다.
 * 묶음 전마다 signal이 취소됐는지 보고, 취소됐으면 거기서 멈춘다(cancelled: true).
 * onProgress(done, total)의 done은 끝난 id 수.
 * step이 던지면 그 오류가 그대로 reject로 나간다(그 전 묶음의 진행은 이미 알린 뒤).
 */
export async function runChunks<T>(
  ids: number[],
  size: number,
  step: (chunk: number[]) => Promise<T>,
  opts: { signal?: AbortSignal; onProgress?: (done: number, total: number) => void },
): Promise<{ results: T[]; cancelled: boolean }> {
  const results: T[] = []
  const n = Math.max(1, size)
  for (let i = 0; i < ids.length; i += n) {
    if (opts.signal?.aborted) return { results, cancelled: true }
    const chunk = ids.slice(i, i + n)
    results.push(await step(chunk))
    opts.onProgress?.(i + chunk.length, ids.length)
  }
  return { results, cancelled: false }
}
