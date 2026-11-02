import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { JobDto } from '../api/types'
import { subscribeProjectStream } from '../lib/projectStream'

interface JobActivity { type: string; jobId: number; kind: string; status: string; done: number; total: number; line: string | null }

/**
 * 정규화 뒤 건 DeepL 번역 작업(TRANSLATE)을 프로젝트 스트림으로 따라간다. 진행 중엔 몇 묶음 중 몇이고,
 * 진행이 올 때마다·끝나면 그리드를 다시 읽어 채워진 행을 보인다. 끝나면 마지막 요약 줄("DeepL 번역 끝: …")을 남긴다.
 */
export function useTranslateJob(projectId: number, cacheKey: number | string) {
  const qc = useQueryClient()
  const [state, setState] = useState({ running: false, done: 0, total: 0 })
  const [summary, setSummary] = useState<string | null>(null)
  const jobId = useRef<number | null>(null)
  const lastLine = useRef<string | null>(null)

  useEffect(() => {
    const refresh = () => {
      qc.invalidateQueries({ queryKey: ['workspace-rows', cacheKey] })
      qc.invalidateQueries({ queryKey: ['workspace-summary', cacheKey] })
    }
    return subscribeProjectStream(projectId, {
      snapshot: (e) => {
        const recent = (JSON.parse(e.data) as { recent?: JobDto[] }).recent ?? []
        const job = recent.find((j) => j.kind === 'TRANSLATE' && j.status === 'RUNNING')
        if (!job) return
        jobId.current = job.id
        setState({ running: true, done: job.doneBatches, total: job.totalBatches })
      },
      activity: (e) => {
        const a = JSON.parse(e.data) as JobActivity
        if (a.kind !== 'TRANSLATE') return
        if (jobId.current !== a.jobId) { jobId.current = a.jobId; lastLine.current = null; setSummary(null) }
        if (a.type === 'log' && a.line) lastLine.current = a.line
        if (a.type === 'done') {
          setState({ running: false, done: a.done, total: a.total })
          setSummary(lastLine.current?.startsWith('DeepL 번역 끝') ? lastLine.current : `번역 작업이 끝났습니다 (${a.status})`)
          refresh()
          return
        }
        setState({ running: true, done: a.done, total: a.total })
        if (a.type === 'progress') refresh()
      },
    })
  }, [projectId, cacheKey, qc])

  return { ...state, summary, dismiss: () => setSummary(null) }
}
