import { API_BASE } from '../api/client'

type Listener = (e: MessageEvent) => void
type Listeners = Record<string, Listener>

interface Shared {
  es: EventSource
  refs: number
  subscribers: Set<Listeners>          // 재연결 시 다시 붙일 구독자들
  failures: number                     // 연속 재연결 실패 횟수(지연 계산용)
  timer: ReturnType<typeof setTimeout> | null
}

const streams = new Map<number, Shared>()

const FIRST_RETRY_MS = 3000
const NEXT_RETRY_MS = 10000

function attach(es: EventSource, listeners: Listeners) {
  for (const [name, fn] of Object.entries(listeners)) es.addEventListener(name, fn as EventListener)
}
function detach(es: EventSource, listeners: Listeners) {
  for (const [name, fn] of Object.entries(listeners)) es.removeEventListener?.(name, fn as EventListener)
}

/** 연결 하나를 열고 현재 구독자들을 모두 붙인다. 완전히 끊기면(readyState 2) 재연결을 예약하는 내부 리스너도 함께 붙인다. */
function openStream(projectId: number, shared: Shared): EventSource {
  const es = new EventSource(`${API_BASE}/projects/${projectId}/jobs/events`)
  es.addEventListener('open', () => { shared.failures = 0 })   // 연결에 성공하면 지연을 초기화한다
  es.addEventListener('error', () => scheduleReopen(projectId, shared, es))
  for (const listeners of shared.subscribers) attach(es, listeners)
  return es
}

/**
 * EventSource가 완전히 닫혔을 때(readyState 2)만 재연결을 예약한다. nginx 502 같은 응답은 치명적 오류라
 * 브라우저가 스스로 재시도하지 않는다. 브라우저가 알아서 재시도 중이면(readyState 0/1) 손대지 않는다.
 */
function scheduleReopen(projectId: number, shared: Shared, deadEs: EventSource) {
  if (shared.es !== deadEs) return   // 이미 교체된 연결에서 온 지난 에러
  if (deadEs.readyState !== 2) return
  if (shared.timer) return   // 이미 재연결이 예약돼 있다
  const delay = shared.failures === 0 ? FIRST_RETRY_MS : NEXT_RETRY_MS
  shared.failures++
  shared.timer = setTimeout(() => {
    shared.timer = null
    if (shared.subscribers.size === 0) return        // 그사이 구독자가 다 빠짐
    if (streams.get(projectId) !== shared) return     // 그사이 스트림 자체가 사라짐
    if (shared.es !== deadEs) return                   // 그사이 다른 경로로 이미 교체됨
    shared.es = openStream(projectId, shared)
  }, delay)
}

function createShared(projectId: number): Shared {
  const shared: Shared = { es: undefined as unknown as EventSource, refs: 0, subscribers: new Set(), failures: 0, timer: null }
  shared.es = openStream(projectId, shared)
  streams.set(projectId, shared)
  return shared
}

/**
 * 프로젝트 SSE 스트림(작업 활동 + 공동 검토 이벤트)을 구독한다. 같은 프로젝트의 구독자들은 연결 하나를 같이 쓴다 —
 * 브라우저가 같은 출처에 여는 연결 수가 6개로 제한돼 있어 화면마다 따로 열면 탭 몇 개로 멈춘다.
 * 연결이 치명적으로 끊기면(예: 백엔드 재배포 중 nginx 502) 지수 백오프로 재연결하고, 살아 있는 구독자들을 다시 붙인다.
 * 돌려주는 함수를 부르면 구독을 끊고, 마지막 구독자가 빠지면 연결도 닫고 예약된 재연결도 취소한다.
 */
export function subscribeProjectStream(projectId: number, listeners: Listeners): () => void {
  const shared = streams.get(projectId) ?? createShared(projectId)
  shared.refs++
  shared.subscribers.add(listeners)
  attach(shared.es, listeners)
  let active = true
  return () => {
    if (!active) return
    active = false
    detach(shared.es, listeners)
    shared.subscribers.delete(listeners)
    if (streams.get(projectId) === shared && --shared.refs === 0) {
      if (shared.timer) { clearTimeout(shared.timer); shared.timer = null }
      shared.es.close()
      streams.delete(projectId)
    }
  }
}

/**
 * 이 프로젝트의 공유 스트림이 이미 열려 있는지(readyState === OPEN) 본다. 재연결 중에도 항상 현재 연결을 본다.
 * 늦게 구독한 쪽은 연결이 열릴 때 오는 `open` 이벤트를 놓치므로, 구독 직후 이걸로 연결 상태를 바로 확인한다.
 */
export function isProjectStreamOpen(projectId: number): boolean {
  const shared = streams.get(projectId)
  return shared?.es.readyState === 1
}
