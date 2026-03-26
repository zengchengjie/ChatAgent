<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { apiJson, ApiError } from '../api/http'
import { consumeSse } from '../utils/sse'
import { marked } from 'marked'

interface SessionRow {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

interface MessageRow {
  id: number
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM'
  content: string | null
  toolCallsJson: string | null
  toolCallId: string | null
  createdAt: string
}

const router = useRouter()
const auth = useAuthStore()

const sessions = ref<SessionRow[]>([])
const activeId = ref<string | null>(null)
const messages = ref<MessageRow[]>([])
const input = ref('')
const busy = ref(false)
const streamPreview = ref('')
const toolEvents = ref<string[]>([])
const banner = ref('')

const title = computed(() => sessions.value.find((s) => s.id === activeId.value)?.title ?? 'Conversation')

async function logout() {
  try {
    await apiJson('/api/auth/logout', { method: 'POST' })
  } catch {
    /* ignore */
  }
  auth.clear()
  await router.push('/login')
}

async function loadSessions() {
  const rows = await apiJson<SessionRow[]>('/api/sessions')
  sessions.value = rows
  if (!activeId.value && rows.length > 0) {
    await selectSession(rows[0].id)
  }
}

async function createSession() {
  const s = await apiJson<SessionRow>('/api/sessions', {
    method: 'POST',
    body: JSON.stringify({}),
  })
  sessions.value = [s, ...sessions.value]
  await selectSession(s.id)
}

async function selectSession(id: string) {
  activeId.value = id
  streamPreview.value = ''
  toolEvents.value = []
  const msgs = await apiJson<MessageRow[]>(`/api/sessions/${id}/messages`)
  messages.value = msgs
}

async function send() {
  const sid = activeId.value
  const text = input.value.trim()
  if (!sid || !text || busy.value) {
    return
  }
  banner.value = ''
  input.value = ''
  busy.value = true
  streamPreview.value = ''
  toolEvents.value = []
  const optimistic: MessageRow = {
    id: -Date.now(),
    role: 'USER',
    content: text,
    toolCallsJson: null,
    toolCallId: null,
    createdAt: new Date().toISOString(),
  }
  messages.value = [...messages.value, optimistic]

  try {
    const res = await fetch('/api/agent/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${auth.token}`,
      },
      body: JSON.stringify({ sessionId: sid, content: text }),
    })
    if (res.status === 429) {
      banner.value = 'Rate limited (429). Please wait a minute.'
      await reloadThread(sid)
      return
    }
    if (!res.ok) {
      const t = await res.text()
      banner.value = t || `Error ${res.status}`
      await reloadThread(sid)
      return
    }
    let acc = ''
    // 后端事件：delta=正文增量；tool_*=侧栏；error/done=结束态（与 AgentService 一致）
    await consumeSse(res, (ev, data) => {
      if (ev === 'delta' && data && typeof data === 'object' && 'text' in data) {
        const piece = String((data as { text?: string }).text ?? '')
        acc += piece
        streamPreview.value = acc
      } else if (ev === 'tool_start' && data && typeof data === 'object') {
        const name = String((data as { name?: string }).name ?? 'tool')
        toolEvents.value = [...toolEvents.value, `start: ${name}`]
      } else if (ev === 'tool_end' && data && typeof data === 'object') {
        const name = String((data as { name?: string }).name ?? 'tool')
        toolEvents.value = [...toolEvents.value, `end: ${name}`]
      } else if (ev === 'error') {
        const msg =
          data && typeof data === 'object' && 'message' in data
            ? String((data as { message?: string }).message)
            : String(data)
        banner.value = msg
      }
    })
    streamPreview.value = ''
    await reloadThread(sid)
    await loadSessions()
  } catch (e) {
    if (e instanceof ApiError) {
      banner.value = e.body
    } else {
      banner.value = 'Request failed'
    }
    await reloadThread(sid)
  } finally {
    busy.value = false
  }
}

async function reloadThread(sid: string) {
  const msgs = await apiJson<MessageRow[]>(`/api/sessions/${sid}/messages`)
  messages.value = msgs
}

onMounted(async () => {
  try {
    await loadSessions()
    if (sessions.value.length === 0) {
      await createSession()
    }
  } catch {
    await router.push('/login')
  }
})

function roleLabel(r: MessageRow['role']): string {
  switch (r) {
    case 'USER':
      return 'You'
    case 'ASSISTANT':
      return 'Assistant'
    case 'TOOL':
      return 'Tool'
    default:
      return r
  }
}

function renderMarkdown(text: string): string {
  const html = marked.parse(text)
  return typeof html === 'string' ? html : ''
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">Chat Agent</div>
      <button class="new-chat" type="button" @click="createSession">+ New chat</button>
      <ul class="sessions">
        <li
          v-for="s in sessions"
          :key="s.id"
          :class="{ active: s.id === activeId }"
          @click="selectSession(s.id)"
        >
          {{ s.title }}
        </li>
      </ul>
      <div class="foot">
        <span class="who">{{ auth.username || 'user' }}</span>
        <button type="button" class="link" @click="logout">Logout</button>
      </div>
    </aside>
    <main class="main">
      <header class="top">
        <h2>{{ title }}</h2>
        <span v-if="banner" class="banner">{{ banner }}</span>
      </header>
      <div class="thread">
        <div v-for="m in messages" :key="m.id" class="bubble-row" :data-role="m.role.toLowerCase()">
          <div class="meta">{{ roleLabel(m.role) }}</div>
          <div class="bubble" v-html="renderMarkdown(m.content || '')"></div>
        </div>
        <div v-if="streamPreview" class="bubble-row" data-role="assistant">
          <div class="meta">Assistant</div>
          <div class="bubble streaming" v-html="renderMarkdown(streamPreview)"></div>
        </div>
      </div>
      <div class="composer">
        <textarea
          v-model="input"
          rows="3"
          placeholder="Message… (try “计算 123*456” or “上海天气”)"
          @keydown.enter.exact.prevent="send"
        />
        <button type="button" class="send" :disabled="busy || !activeId" @click="send">
          {{ busy ? '…' : 'Send' }}
        </button>
      </div>
    </main>
    <aside class="tools">
      <h3>Tools</h3>
      <p v-if="!toolEvents.length" class="muted">Tool steps appear here during a reply.</p>
      <ul>
        <li v-for="(t, i) in toolEvents" :key="i">{{ t }}</li>
      </ul>
    </aside>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 240px 1fr 200px;
  min-height: 100vh;
  background: var(--bg);
  color: var(--text);
}
.sidebar {
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 1rem;
  gap: 0.75rem;
  background: var(--panel);
}
.brand {
  font-weight: 700;
  letter-spacing: 0.02em;
}
.new-chat {
  padding: 0.45rem 0.6rem;
  border-radius: 8px;
  border: 1px dashed var(--border);
  background: transparent;
  color: var(--text);
  cursor: pointer;
}
.sessions {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
  overflow: auto;
}
.sessions li {
  padding: 0.5rem 0.55rem;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  color: var(--muted);
}
.sessions li.active {
  background: rgba(99, 102, 241, 0.15);
  color: var(--text);
}
.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.8rem;
  color: var(--muted);
}
.link {
  background: none;
  border: none;
  color: #93c5fd;
  cursor: pointer;
}
.main {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.top {
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.top h2 {
  margin: 0;
  font-size: 1.1rem;
}
.banner {
  color: #fbbf24;
  font-size: 0.85rem;
}
.thread {
  flex: 1;
  overflow: auto;
  padding: 1rem 1.25rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
}
.bubble-row {
  max-width: 720px;
}
.bubble-row[data-role='user'] .bubble {
  background: rgba(59, 130, 246, 0.2);
  border-color: rgba(59, 130, 246, 0.35);
}
.bubble-row[data-role='assistant'] .bubble {
  background: rgba(148, 163, 184, 0.12);
}
.bubble-row[data-role='tool'] .bubble {
  background: rgba(34, 197, 94, 0.12);
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
}
.meta {
  font-size: 0.75rem;
  color: var(--muted);
  margin-bottom: 0.2rem;
}
.bubble {
  padding: 0.65rem 0.85rem;
  border-radius: 12px;
  border: 1px solid var(--border);
  line-height: 1.6;
}
.bubble :deep(*) {
  margin: 0.5em 0;
}
.bubble :deep(p) {
  margin: 0.5em 0;
}
.bubble :deep(ul),
.bubble :deep(ol) {
  margin: 0.5em 0 0.5em 1.5em;
}
.bubble :deep(code) {
  background: rgba(0, 0, 0, 0.1);
  padding: 0.2em 0.4em;
  border-radius: 3px;
  font-family: ui-monospace, monospace;
  font-size: 0.9em;
}
.bubble :deep(pre) {
  background: rgba(0, 0, 0, 0.2);
  padding: 0.8em;
  border-radius: 8px;
  overflow-x: auto;
}
.bubble :deep(pre code) {
  background: transparent;
  padding: 0;
}
.bubble :deep(h1),
.bubble :deep(h2),
.bubble :deep(h3) {
  margin: 0.8em 0 0.4em;
  font-weight: 600;
}
.bubble :deep(h1) { font-size: 1.5em; }
.bubble :deep(h2) { font-size: 1.3em; }
.bubble :deep(h3) { font-size: 1.1em; }
.bubble :deep(blockquote) {
  border-left: 3px solid var(--border);
  margin: 0.5em 0;
  padding: 0.5em 1em;
  color: var(--muted);
  background: rgba(0, 0, 0, 0.05);
}
.streaming {
  border-style: dashed;
}
.composer {
  border-top: 1px solid var(--border);
  padding: 0.85rem 1.25rem 1.25rem;
  display: flex;
  gap: 0.75rem;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.2);
}
textarea {
  flex: 1;
  resize: vertical;
  min-height: 72px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--input-bg);
  color: var(--text);
  padding: 0.6rem 0.75rem;
  font: inherit;
}
.send {
  padding: 0.65rem 1.1rem;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
  color: white;
  font-weight: 600;
  cursor: pointer;
  height: fit-content;
}
.send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.tools {
  border-left: 1px solid var(--border);
  padding: 1rem;
  font-size: 0.8rem;
  color: var(--muted);
  background: var(--panel);
}
.tools h3 {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
  color: var(--text);
}
.tools ul {
  margin: 0;
  padding-left: 1rem;
}
.muted {
  color: var(--muted);
}
@media (max-width: 960px) {
  .layout {
    grid-template-columns: 200px 1fr;
  }
  .tools {
    display: none;
  }
}
</style>
