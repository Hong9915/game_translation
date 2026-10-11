import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { api } from './client'

export function useProjectId(): number | undefined {
  const { id } = useParams<{ id: string }>()
  return id ? Number(id) : undefined
}

export function useProject(id: number | undefined) {
  return useQuery({ queryKey: ['project', id], queryFn: () => api.projects.get(id as number), enabled: id !== undefined })
}
