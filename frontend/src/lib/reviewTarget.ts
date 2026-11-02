/**
 * 리뷰 범위(설계 2026-10-12). 파일 화면은 프로젝트의 시트 하나, 게임 화면은 고른 설정 시트 id들(비면 게임 전체).
 * 서버는 파일 화면을 /review/{projectId}/…?sheet=, 게임 화면을 /games/{gameId}/review/…?sheets= 로 받는다.
 */
export type ReviewTarget =
  | { kind: 'project'; projectId: number; sheet: string }
  | { kind: 'game'; gameId: number; sheetIds: number[] }

/**
 * 화면 하나를 가리키는 수(명령 실행 보관소의 키). 파일 화면은 프로젝트 id, 게임 화면은 -게임 id —
 * 프로젝트 id는 늘 양수라 겹치지 않는다(서버 PresenceService.gameRoom과 같은 규칙).
 */
export const screenOf = (t: ReviewTarget): number => (t.kind === 'project' ? t.projectId : -t.gameId)

export const sameTarget = (a: ReviewTarget | null | undefined, b: ReviewTarget | null | undefined): boolean =>
  !!a && !!b && JSON.stringify(a) === JSON.stringify(b)
