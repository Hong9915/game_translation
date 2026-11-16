import { useQuery } from '@tanstack/react-query'
import { TaskItem, TaskList } from '@tiptap/extension-list'
import { FontSize, TextStyle } from '@tiptap/extension-text-style'
import { Placeholder } from '@tiptap/extensions'
import { EditorContent, useEditor, type Editor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { ApiError, api } from '../../api/client'
import type { GameMemo } from '../../api/types'
import { relativeTime } from '../../lib/relativeTime'

export interface MemoPanelProps {
  gameId: number
  gameName?: string
  /** 입력이 멈춘 뒤 저장까지 기다리는 시간(ms). */
  saveDelay?: number
}

/** 다른 사람이 고친 내용을 가져오는 주기. 실시간 공동 편집이 아니라 "곧 보이는" 정도면 된다. */
const POLL_MS = 15_000
/** 창이 닫힐 때 보내는 요청(keepalive)의 브라우저 한도 64KB보다 조금 작게. 바이트 기준이다 — 한글은 글자당 3바이트. */
const KEEPALIVE_MAX_BYTES = 60_000
/** 크기를 따로 안 고른 글자의 처음 크기. 드래그 없이 다른 크기를 고르면 그 사람의 기본값이 된다(브라우저별). */
const DEFAULT_FONT_SIZE = 32
const FONT_SIZES = [12, 14, 16, 18, 20, 24, 28, 32, 40]
const BASE_SIZE_KEY = 'memo-base-font-size'

function loadBaseSize(): number {
  try {
    const saved = Number(localStorage.getItem(BASE_SIZE_KEY))
    return FONT_SIZES.includes(saved) ? saved : DEFAULT_FONT_SIZE
  } catch { return DEFAULT_FONT_SIZE }
}

function saveBaseSize(size: number) {
  try { localStorage.setItem(BASE_SIZE_KEY, String(size)) } catch { /* 저장 못 해도 이번 창에서는 적용된다 */ }
}
const EXTENSIONS = [
  StarterKit.configure({ heading: { levels: [2] } }),
  TextStyle,
  FontSize,
  TaskList,
  TaskItem.configure({ nested: true }),
  Placeholder.configure({ placeholder: '이 게임에 대해 팀이 같이 볼 메모를 적어 보세요.' }),
]

type SaveState = 'idle' | 'saving' | 'error'

/** 저장된 문자열을 문서로. 깨진 값이면 빈 문서로 열어 패널이 죽지 않게 한다. */
function parseDoc(content: string | null) {
  if (!content) return ''
  try { return JSON.parse(content) } catch { return '' }
}

/** 새 창에 뜨는 메모 — 게임마다 한 장, 입력이 멈추면 자동 저장한다. */
export default function MemoPanel({ gameId, gameName, saveDelay = 800 }: MemoPanelProps) {
  const memo = useQuery({ queryKey: ['game-memo', gameId], queryFn: () => api.games.memo(gameId), refetchInterval: POLL_MS, gcTime: 0 })
  const [loaded, setLoaded] = useState(false)
  const [saved, setSaved] = useState<GameMemo | null>(null)
  const [saveState, setSaveState] = useState<SaveState>('idle')
  const [saveError, setSaveError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [baseSize, setBaseSize] = useState(loadBaseSize)
  const changeBaseSize = (size: number) => { setBaseSize(size); saveBaseSize(size) }

  // 저장 흐름은 렌더와 무관하게 돌고 패널이 닫힌 뒤에도 끝나야 하므로 ref로 든다.
  const versionRef = useRef(0)
  const latestRef = useRef('')
  const dirtyRef = useRef(false)
  const savingRef = useRef(false)
  const conflictRef = useRef(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const flush = useCallback(async () => {
    clearTimeout(timerRef.current)
    if (savingRef.current || !dirtyRef.current || conflictRef.current) return
    savingRef.current = true
    setSaveState('saving')
    try {
      // 저장하는 동안 더 친 글이 있으면 이어서 저장한다.
      while (dirtyRef.current) {
        dirtyRef.current = false
        const r = await api.games.saveMemo(gameId, { content: latestRef.current, baseVersion: versionRef.current })
        versionRef.current = r.version
        setSaved(r)
      }
      setSaveState('idle')
      setSaveError(null)
    } catch (e) {
      dirtyRef.current = true
      if (e instanceof ApiError && e.status === 409) {
        conflictRef.current = true
        setConflict(true)
        setSaveState('idle')
      } else {
        setSaveState('error')
        setSaveError(e instanceof ApiError ? e.message : '저장하지 못했습니다.')
      }
    } finally {
      savingRef.current = false
    }
  }, [gameId])

  const editor = useEditor({
    extensions: EXTENSIONS,
    content: '',
    editable: false,
    shouldRerenderOnTransaction: true,
    editorProps: { attributes: { 'aria-label': '메모 내용', class: 'memo-editor' } },
    onUpdate: ({ editor: e }) => {
      latestRef.current = JSON.stringify(e.getJSON())
      dirtyRef.current = true
      clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => void flush(), saveDelay)
    },
  })

  const applyServer = useCallback((d: GameMemo, target: Editor) => {
    target.commands.setContent(parseDoc(d.content), { emitUpdate: false })
    versionRef.current = d.version
    latestRef.current = d.content ?? ''
    dirtyRef.current = false
    setSaved(d)
  }, [])

  // 처음 불러온 내용을 넣고 나서야 쓸 수 있게 한다 — 불러오기 전에 친 글이 덮이거나, 빈 문서로 저장되지 않게.
  // 그 뒤로는 내가 안 쓰고 있을 때만 다른 사람의 저장을 가져온다.
  const data = memo.data
  useEffect(() => {
    if (!data || !editor) return
    if (!loaded) {
      applyServer(data, editor)
      editor.setEditable(true, false)
      setLoaded(true)
    } else if (data.version > versionRef.current && !dirtyRef.current && !savingRef.current && !(editor.isFocused && document.hasFocus())) {
      applyServer(data, editor)
    }
  }, [data, editor, loaded, applyServer])

  // 화면을 벗어나도 쓰던 글은 저장한다. flush는 latestRef만 읽어 에디터가 사라진 뒤에도 안전하다.
  useEffect(() => () => { void flush() }, [flush])

  // 창을 닫는 순간: 보통은 묻지 않고 keepalive로 저장한다. 그렇게 보낼 수 없는 경우(저장이 진행 중, 충돌 대기, 너무 김)만 닫기 전에 묻는다.
  useEffect(() => {
    const canSendOnClose = () => !savingRef.current && !conflictRef.current
      && new TextEncoder().encode(latestRef.current).length <= KEEPALIVE_MAX_BYTES
    const warn = (e: BeforeUnloadEvent) => { if (dirtyRef.current && !canSendOnClose()) e.preventDefault() }
    const saveOnClose = () => {
      if (!dirtyRef.current || !canSendOnClose()) return
      clearTimeout(timerRef.current)
      dirtyRef.current = false
      void api.games.saveMemo(gameId, { content: latestRef.current, baseVersion: versionRef.current }, { keepalive: true }).catch(() => {})
    }
    window.addEventListener('beforeunload', warn)
    window.addEventListener('pagehide', saveOnClose)
    return () => {
      window.removeEventListener('beforeunload', warn)
      window.removeEventListener('pagehide', saveOnClose)
    }
  }, [gameId])

  const resolveConflict = async (keepMine: boolean) => {
    const { data: latest } = await memo.refetch()
    if (!latest || !editor) return
    conflictRef.current = false
    setConflict(false)
    if (keepMine) {
      versionRef.current = latest.version
      dirtyRef.current = true
      void flush()
    } else {
      applyServer(latest, editor)
    }
  }

  const status = saveState === 'saving' ? '저장 중…'
    : saved?.updatedAt ? `${saved.updatedByName ? `${saved.updatedByName} · ` : ''}${relativeTime(saved.updatedAt)} 저장됨`
    : ''

  return (
    <main className="flex h-screen w-full flex-col bg-white text-sm text-ink">
      <div className="flex items-center gap-2 px-4 pb-2 pt-3">
        <h1 className="flex-none text-[15px] font-semibold tracking-normal">메모</h1>
        {gameName && <span className="min-w-0 truncate text-xs text-body">{gameName}</span>}
        <div className="flex-1" />
        <span role="status" className="min-w-0 truncate text-[11px] text-muted">{status}</span>
      </div>
      {editor && <Toolbar editor={editor} disabled={!loaded} baseSize={baseSize} onBaseSize={changeBaseSize} />}
      {conflict && (
        <div role="alert" className="mx-3 mt-2 rounded-lg bg-orangeSoft px-3 py-2 text-[12px] text-ink">
          <p className="break-keep">다른 분이 먼저 메모를 고쳐서 저장을 멈췄습니다.</p>
          <div className="mt-1.5 flex gap-1.5">
            <button type="button" onClick={() => void resolveConflict(false)} className="press rounded-md border border-line bg-white px-2 py-0.5 text-[11px]">최신 내용 불러오기 (내 글 버림)</button>
            <button type="button" onClick={() => void resolveConflict(true)} className="press rounded-md bg-orange px-2 py-0.5 text-[11px] text-white">내 글로 덮어쓰기</button>
          </div>
        </div>
      )}
      {saveState === 'error' && (
        <div role="alert" className="mx-3 mt-2 flex items-center gap-2 rounded-lg bg-orangeSoft px-3 py-2 text-[12px]">
          <span className="min-w-0 flex-1 break-keep">{saveError}</span>
          <button type="button" onClick={() => void flush()} className="press flex-none rounded-md bg-orange px-2 py-0.5 text-[11px] text-white">다시 저장</button>
        </div>
      )}
      {memo.isError && !loaded && <p role="alert" className="px-4 py-3 text-[13px] text-orange">메모를 불러오지 못했습니다.</p>}
      <div className="min-h-0 flex-1 overflow-y-auto" style={{ '--memo-font-size': `${baseSize}px` } as CSSProperties}>
        <EditorContent editor={editor} className="h-full" />
      </div>
    </main>
  )
}

function Toolbar({ editor, disabled, baseSize, onBaseSize }: { editor: Editor; disabled: boolean; baseSize: number; onBaseSize: (size: number) => void }) {
  const chain = () => editor.chain().focus()
  const items: { label: string; glyph: string; className?: string; active: boolean; run: () => void }[] = [
    { label: '제목', glyph: '제목', active: editor.isActive('heading', { level: 2 }), run: () => chain().toggleHeading({ level: 2 }).run() },
    { label: '굵게', glyph: 'B', className: 'font-bold', active: editor.isActive('bold'), run: () => chain().toggleBold().run() },
    { label: '기울임', glyph: 'I', className: 'italic', active: editor.isActive('italic'), run: () => chain().toggleItalic().run() },
    { label: '취소선', glyph: 'S', className: 'line-through', active: editor.isActive('strike'), run: () => chain().toggleStrike().run() },
    { label: '글머리 목록', glyph: '•', active: editor.isActive('bulletList'), run: () => chain().toggleBulletList().run() },
    { label: '번호 목록', glyph: '1.', active: editor.isActive('orderedList'), run: () => chain().toggleOrderedList().run() },
    { label: '체크리스트', glyph: '☑', active: editor.isActive('taskList'), run: () => chain().toggleTaskList().run() },
  ]
  // 붙여넣은 글은 목록에 없는 크기일 수 있다 — 그대로 보이게 목록에 끼워 준다.
  const px = /^(\d+(?:\.\d+)?)px$/.exec(String(editor.getAttributes('textStyle').fontSize ?? ''))
  const size = px ? Number(px[1]) : baseSize
  const sizes = FONT_SIZES.includes(size) ? FONT_SIZES : [...FONT_SIZES, size].sort((a, b) => a - b)
  // 드래그한 글자가 있으면 그 부분만 바꾸고, 없으면 내 기본값을 바꾼다 — 한 단어를 키웠다고 메모 전체가 커지지 않게.
  // 기본 크기와 같으면 크기 표시를 지운다 — 문서에 불필요한 값을 남기지 않게.
  const pickSize = (next: number) => {
    if (editor.state.selection.empty) {
      onBaseSize(next)
      chain().unsetFontSize().run()
    } else if (next === baseSize) {
      chain().unsetFontSize().run()
    } else {
      chain().setFontSize(`${next}px`).run()
    }
  }
  return (
    <div role="toolbar" aria-label="메모 서식" className="flex items-center gap-0.5 border-b border-lineSoft px-3 pb-1.5">
      <select aria-label="글자 크기" title="글자 크기" disabled={disabled} value={size} onChange={(e) => pickSize(Number(e.currentTarget.value))}
        className="mr-1 h-7 rounded-md border border-line bg-white px-1 text-[12px] text-body disabled:text-muted">
        {sizes.map((s) => <option key={s} value={s}>{s}</option>)}
      </select>
      {items.map((b) => (
        <button key={b.label} type="button" aria-label={b.label} aria-pressed={b.active} title={b.label} disabled={disabled} onClick={b.run}
          className={`press flex h-7 min-w-7 items-center justify-center rounded-md px-1.5 text-[12px] disabled:text-muted ${b.active ? 'bg-purpleSoft text-purple' : 'text-body hover:bg-canvas'} ${b.className ?? ''}`}>
          {b.glyph}
        </button>
      ))}
    </div>
  )
}
