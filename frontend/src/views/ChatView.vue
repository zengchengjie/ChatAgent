<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { apiJson, ApiError } from '../api/http'
import { consumeSse } from '../utils/sse'
import { marked } from 'marked'

interface SessionRow {
  id: string
  title: string
  model?: string
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

interface TimelineEvent {
  type: 'plan_start' | 'plan_step' | 'plan_done' | 'tool_start' | 'tool_end' | 'guardrail'
  text: string
  stepIndex?: number
  toolName?: string
  status?: 'pending' | 'running' | 'success' | 'error'
  detail?: string
  timestamp?: string
}

const router = useRouter()
const auth = useAuthStore()

const sessions = ref<SessionRow[]>([])
const activeId = ref<string | null>(null)
const messages = ref<MessageRow[]>([])
const input = ref('')
const busy = ref(false)
const streamPreview = ref('')
const timelineEvents = ref<TimelineEvent[]>([])
const banner = ref('')
const sidebarOpen = ref(false)
const toolsOpen = ref(false)
const searchQuery = ref('')
const editingSessionId = ref<string | null>(null)
const editingTitle = ref('')
const showSessionMenu = ref<string | null>(null)

const allowedModels = ref<string[]>([])
const selectedModel = ref<string>('qwen3.5-flash')

const title = computed(() => sessions.value.find((s) => s.id === activeId.value)?.title ?? 'Conversation')

const filteredSessions = computed(() => {
  if (!searchQuery.value.trim()) {
    return sessions.value
  }
  const query = searchQuery.value.toLowerCase()
  return sessions.value.filter(s => 
    s.title.toLowerCase().includes(query)
  )
})

const threadEl = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)

function nearBottom(el: HTMLElement, thresholdPx = 80): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= thresholdPx
}

function onThreadScroll(): void {
  const el = threadEl.value
  if (!el) return
  stickToBottom.value = nearBottom(el)
}

async function scrollThreadToBottom(): Promise<void> {
  const el = threadEl.value
  if (!el) return
  await nextTick()
  // 尽量不抖动：用 rAF 等 DOM 更新完成
  requestAnimationFrame(() => {
    el.scrollTop = el.scrollHeight
  })
}

async function followIfNeeded(): Promise<void> {
  if (!stickToBottom.value) return
  await scrollThreadToBottom()
}

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

async function loadModels() {
  try {
    const ms = await apiJson<string[]>('/api/models')
    if (Array.isArray(ms) && ms.length > 0) {
      allowedModels.value = ms
      if (!ms.includes(selectedModel.value)) {
        selectedModel.value = ms[0]
      }
    }
  } catch {
    // degrade: keep default
  }
}

async function createSession() {
  const s = await apiJson<SessionRow>('/api/sessions', {
    method: 'POST',
    body: JSON.stringify({ model: selectedModel.value }),
  })
  sessions.value = [s, ...sessions.value]
  await selectSession(s.id)
}

async function deleteSession(id: string) {
  const target = sessions.value.find((s) => s.id === id)
  const name = target?.title || 'this chat'
  const ok = window.confirm(`Delete "${name}"? This action cannot be undone.`)
  if (!ok) {
    return
  }
  banner.value = ''
  await apiJson<void>(`/api/sessions/${id}`, { method: 'DELETE' })
  const remaining = sessions.value.filter((s) => s.id !== id)
  sessions.value = remaining

  if (remaining.length === 0) {
    messages.value = []
    activeId.value = null
    await createSession()
    return
  }

  if (activeId.value === id) {
    await selectSession(remaining[0].id)
  }
}

async function renameSession(id: string) {
  const session = sessions.value.find((s) => s.id === id)
  if (!session) return
  
  editingSessionId.value = id
  editingTitle.value = session.title
}

async function saveRename() {
  if (!editingSessionId.value) return
  
  try {
    const updated = await apiJson<SessionRow>(`/api/sessions/${editingSessionId.value}`, {
      method: 'PATCH',
      body: JSON.stringify({ title: editingTitle.value }),
    })
    
    const index = sessions.value.findIndex(s => s.id === editingSessionId.value)
    if (index >= 0) {
      sessions.value[index] = updated
    }
  } catch (e) {
    banner.value = 'Failed to rename session'
  } finally {
    editingSessionId.value = null
    editingTitle.value = ''
  }
}

function cancelRename() {
  editingSessionId.value = null
  editingTitle.value = ''
}

async function exportSession(id: string) {
  const session = sessions.value.find((s) => s.id === id)
  if (!session) return
  
  try {
    const msgs = await apiJson<MessageRow[]>(`/api/sessions/${id}/messages`)
    const exportData = {
      session: session,
      messages: msgs,
      exportedAt: new Date().toISOString()
    }
    
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `chat-${session.title.replace(/[^a-z0-9]/gi, '-')}-${Date.now()}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e) {
    banner.value = 'Failed to export session'
  }
}

function toggleSessionMenu(id: string) {
  if (showSessionMenu.value === id) {
    showSessionMenu.value = null
  } else {
    showSessionMenu.value = id
  }
}

async function selectSession(id: string) {
  activeId.value = id
  streamPreview.value = ''
  timelineEvents.value = []
  const msgs = await apiJson<MessageRow[]>(`/api/sessions/${id}/messages`)
  messages.value = msgs
  stickToBottom.value = true
  await scrollThreadToBottom()
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
  timelineEvents.value = []
  stickToBottom.value = true
  const optimistic: MessageRow = {
    id: -Date.now(),
    role: 'USER',
    content: text,
    toolCallsJson: null,
    toolCallId: null,
    createdAt: new Date().toISOString(),
  }
  messages.value = [...messages.value, optimistic]
  await scrollThreadToBottom()

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
    await consumeSse(res, (ev, data) => {
      if (ev === 'delta' && data && typeof data === 'object' && 'text' in data) {
        const piece = String((data as { text?: string }).text ?? '')
        acc += piece
        streamPreview.value = acc
        void followIfNeeded()
      } else if (ev === 'plan_start' && data && typeof data === 'object') {
        const count = Number((data as { count?: number }).count ?? 0)
        timelineEvents.value = [
          ...timelineEvents.value,
          { 
            type: 'plan_start', 
            text: `Plan started (${count} step${count === 1 ? '' : 's'})`,
            timestamp: new Date().toISOString()
          },
        ]
      } else if (ev === 'plan_step' && data && typeof data === 'object') {
        const stepIndex = Number((data as { stepIndex?: number }).stepIndex ?? 0)
        const text = String((data as { text?: string }).text ?? '')
        timelineEvents.value = [
          ...timelineEvents.value,
          { 
            type: 'plan_step', 
            text: text,
            stepIndex,
            status: 'success',
            timestamp: new Date().toISOString()
          },
        ]
      } else if (ev === 'plan_done') {
        timelineEvents.value = [...timelineEvents.value, { 
          type: 'plan_done', 
          text: 'Plan completed',
          status: 'success',
          timestamp: new Date().toISOString()
        }]
      } else if (ev === 'tool_start' && data && typeof data === 'object') {
        const name = String((data as { name?: string }).name ?? 'tool')
        timelineEvents.value = [
          ...timelineEvents.value,
          { 
            type: 'tool_start', 
            text: `Executing: ${name}`,
            toolName: name,
            status: 'running',
            timestamp: new Date().toISOString()
          },
        ]
      } else if (ev === 'tool_end' && data && typeof data === 'object') {
        const name = String((data as { name?: string }).name ?? 'tool')
        const ok = (data as { ok?: boolean }).ok ?? true
        const detail = String((data as { detail?: string }).detail ?? '')
        const eventIndex = timelineEvents.value.findIndex(
          e => e.type === 'tool_start' && e.toolName === name && e.status === 'running'
        )
        if (eventIndex >= 0) {
          const updatedEvents = [...timelineEvents.value]
          updatedEvents[eventIndex] = {
            ...updatedEvents[eventIndex],
            type: 'tool_end',
            text: `Completed: ${name}`,
            status: ok ? 'success' : 'error',
            detail: detail,
            timestamp: new Date().toISOString()
          }
          timelineEvents.value = updatedEvents
        }
      } else if (ev === 'guardrail' && data && typeof data === 'object') {
        const reason = String((data as { reason?: string }).reason ?? 'unknown')
        const limit = Number((data as { limit?: number }).limit ?? 0)
        const actual = Number((data as { actual?: number }).actual ?? 0)
        timelineEvents.value = [
          ...timelineEvents.value,
          {
            type: 'guardrail',
            text: `Guardrail triggered: ${reason} (limit: ${limit}, actual: ${actual})`,
            status: 'error',
            timestamp: new Date().toISOString()
          }
        ]
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
  await followIfNeeded()
}

onMounted(async () => {
  try {
    await loadModels()
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

function formatTime(isoString: string): string {
  const date = new Date(isoString)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffSecs = Math.floor(diffMs / 1000)
  
  if (diffSecs < 60) return `${diffSecs}s ago`
  if (diffSecs < 3600) return `${Math.floor(diffSecs / 60)}m ago`
  if (diffSecs < 86400) return `${Math.floor(diffSecs / 3600)}h ago`
  return date.toLocaleDateString()
}
</script>

<template>
  <div class="layout">
    <div class="sidebar-overlay" :class="{ open: sidebarOpen }" @click="sidebarOpen = false"></div>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="brand">Chat Agent</div>
      <div class="model-picker">
        <label class="model-label">Model</label>
        <select v-model="selectedModel" class="model-select">
          <option v-for="m in allowedModels" :key="m" :value="m">{{ m }}</option>
          <option v-if="allowedModels.length === 0" :value="selectedModel">{{ selectedModel }}</option>
        </select>
      </div>
      <button class="new-chat" type="button" @click="createSession">+ New chat</button>
      <div class="search-box">
        <input 
          v-model="searchQuery" 
          type="text" 
          placeholder="Search sessions..." 
          class="search-input"
        />
      </div>
      <ul class="sessions">
        <li
          v-for="s in filteredSessions"
          :key="s.id"
          :class="{ active: s.id === activeId }"
          @click="selectSession(s.id)"
        >
          <div v-if="editingSessionId === s.id" class="session-edit">
            <input
              v-model="editingTitle"
              type="text"
              class="edit-input"
              @click.stop
              @keydown.enter="saveRename"
              @keydown.esc="cancelRename"
            />
            <button class="save-btn" type="button" @click.stop="saveRename">✓</button>
            <button class="cancel-btn" type="button" @click.stop="cancelRename">✕</button>
          </div>
          <template v-else>
            <span class="session-title">{{ s.title }}</span>
            <div class="session-actions">
              <button
                class="menu-btn-small"
                type="button"
                @click.stop="toggleSessionMenu(s.id)"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="1"></circle>
                  <circle cx="12" cy="5" r="1"></circle>
                  <circle cx="12" cy="19" r="1"></circle>
                </svg>
              </button>
              <div v-if="showSessionMenu === s.id" class="session-dropdown" @click.stop>
                <button type="button" @click="renameSession(s.id); showSessionMenu = null">
                  Rename
                </button>
                <button type="button" @click="exportSession(s.id); showSessionMenu = null">
                  Export
                </button>
                <button type="button" @click="deleteSession(s.id); showSessionMenu = null">
                  Delete
                </button>
              </div>
            </div>
          </template>
        </li>
      </ul>
      <div class="foot">
        <span class="who">{{ auth.username || 'user' }}</span>
        <button type="button" class="link" @click="logout">Logout</button>
      </div>
    </aside>
    <main class="main">
      <header class="top">
        <div class="header-left">
          <button class="menu-btn" @click="sidebarOpen = !sidebarOpen" aria-label="Toggle menu">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="3" y1="6" x2="21" y2="6"></line>
              <line x1="3" y1="12" x2="21" y2="12"></line>
              <line x1="3" y1="18" x2="21" y2="18"></line>
            </svg>
          </button>
          <h2>{{ title }}</h2>
        </div>
        <div class="header-right">
          <button class="tools-btn" @click="toolsOpen = !toolsOpen" aria-label="Toggle tools">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"></circle>
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
            </svg>
          </button>
          <span v-if="banner" class="banner">{{ banner }}</span>
        </div>
      </header>
      <div ref="threadEl" class="thread" @scroll="onThreadScroll">
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
          placeholder="Message… (try &quot;计算 123*456&quot; or &quot;上海天气&quot;)"
          @keydown.enter.exact.prevent="send"
        />
        <button type="button" class="send" :disabled="busy || !activeId" @click="send">
          {{ busy ? '…' : 'Send' }}
        </button>
      </div>
    </main>
    <div class="tools-overlay" :class="{ open: toolsOpen }" @click="toolsOpen = false"></div>
    <aside class="tools" :class="{ open: toolsOpen }">
      <div class="tools-header">
        <h3>Process</h3>
        <button class="close-btn" @click="toolsOpen = false" aria-label="Close tools">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>
      <p v-if="!timelineEvents.length" class="muted">Plan and tool steps appear here during a reply.</p>
      <ul>
        <li v-for="(t, i) in timelineEvents" :key="i" :class="`event-${t.type} status-${t.status || 'default'}`">
          <div class="event-content">
            <div class="event-header">
              <span class="event-icon">
                <svg v-if="t.type === 'plan_start'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M9 11l3 3L22 4"></path>
                  <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>
                </svg>
                <svg v-else-if="t.type === 'plan_step'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"></circle>
                  <polyline points="12 6 12 12 16 14"></polyline>
                </svg>
                <svg v-else-if="t.type === 'plan_done'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
                  <polyline points="22 4 12 14.01 9 11.01"></polyline>
                </svg>
                <svg v-else-if="t.type === 'tool_start' || t.type === 'tool_end'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
                </svg>
                <svg v-else-if="t.type === 'guardrail'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
                  <line x1="12" y1="9" x2="12" y2="13"></line>
                  <line x1="12" y1="17" x2="12.01" y2="17"></line>
                </svg>
              </span>
              <span class="event-text">{{ t.text }}</span>
            </div>
            <div v-if="t.detail" class="event-detail">{{ t.detail }}</div>
            <div v-if="t.timestamp" class="event-time">{{ formatTime(t.timestamp) }}</div>
          </div>
        </li>
      </ul>
    </aside>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 240px 1fr 200px;
  height: 100vh;
  overflow: hidden;
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
  min-height: 0;
}
.brand {
  font-weight: 700;
  letter-spacing: 0.02em;
}
.model-picker {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.model-label {
  font-size: 0.8rem;
  opacity: 0.8;
}
.model-select {
  width: 100%;
  padding: 0.45rem 0.6rem;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--input-bg);
  color: var(--text);
  font-size: 0.85rem;
}
.new-chat {
  padding: 0.45rem 0.6rem;
  border-radius: 8px;
  border: 1px dashed var(--border);
  background: transparent;
  color: var(--text);
  cursor: pointer;
}

.search-box {
  margin-bottom: 0.5rem;
}

.search-input {
  width: 100%;
  padding: 0.5rem 0.6rem;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--input-bg);
  color: var(--text);
  font-size: 0.85rem;
}

.search-input:focus {
  outline: none;
  border-color: rgba(99, 102, 241, 0.5);
}
.sessions {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
  overflow: auto;
}
.sessions li {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.55rem;
  border-radius: 8px;
  cursor: pointer;
  font-size: 0.9rem;
  color: var(--muted);
  position: relative;
}

.session-edit {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  width: 100%;
}

.edit-input {
  flex: 1;
  padding: 0.3rem 0.4rem;
  border-radius: 4px;
  border: 1px solid var(--border);
  background: var(--input-bg);
  color: var(--text);
  font-size: 0.85rem;
}

.edit-input:focus {
  outline: none;
  border-color: rgba(99, 102, 241, 0.5);
}

.save-btn,
.cancel-btn {
  border: none;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  font-size: 1rem;
  padding: 0.2rem 0.4rem;
  border-radius: 4px;
}

.save-btn:hover {
  color: #4ade80;
  background: rgba(74, 222, 128, 0.1);
}

.cancel-btn:hover {
  color: #f87171;
  background: rgba(248, 113, 113, 0.1);
}
.session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-delete {
  border: none;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font-size: 0.9rem;
  border-radius: 6px;
  padding: 0.1rem 0.3rem;
}

.session-delete:hover {
  color: #fda4af;
  background: rgba(248, 113, 113, 0.12);
}

.session-actions {
  display: flex;
  align-items: center;
  gap: 0.2rem;
}

.menu-btn-small {
  border: none;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font-size: 0.9rem;
  border-radius: 6px;
  padding: 0.2rem 0.3rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.menu-btn-small:hover {
  color: var(--text);
  background: rgba(99, 102, 241, 0.1);
}

.session-dropdown {
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 0.25rem;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  z-index: 10;
  min-width: 120px;
  display: flex;
  flex-direction: column;
}

.session-dropdown button {
  text-align: left;
  padding: 0.5rem 0.75rem;
  border: none;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  font-size: 0.85rem;
  border-radius: 0;
}

.session-dropdown button:first-child {
  border-radius: 8px 8px 0 0;
}

.session-dropdown button:last-child {
  border-radius: 0 0 8px 8px;
  color: #f87171;
}

.session-dropdown button:hover {
  background: rgba(99, 102, 241, 0.1);
}

.session-dropdown button:last-child:hover {
  background: rgba(248, 113, 113, 0.1);
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
  min-height: 0;
}
.top {
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.header-right {
  justify-content: flex-end;
}

.menu-btn,
.tools-btn,
.close-btn {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0.5rem;
  cursor: pointer;
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
}

.menu-btn:hover,
.tools-btn:hover,
.close-btn:hover {
  background: rgba(99, 102, 241, 0.1);
  border-color: rgba(99, 102, 241, 0.3);
}

.tools-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}

.sidebar-overlay,
.tools-overlay {
  display: none;
}

.top h2 {
  margin: 0;
  font-size: 1.1rem;
}

@media (max-width: 960px) {
  .sidebar-overlay.open,
  .tools-overlay.open {
    display: block;
  }
}
.banner {
  color: #fbbf24;
  font-size: 0.85rem;
}
.thread {
  flex: 1;
  min-height: 0;
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
  min-height: 0;
  overflow: auto;
}
.tools h3 {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
  color: var(--text);
}
.tools ul {
  margin: 0;
  padding-left: 0;
  list-style: none;
}

.tools li {
  margin: 0.5rem 0;
  padding: 0.75rem;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid var(--border);
  transition: all 0.2s ease;
}

.tools li:hover {
  background: rgba(0, 0, 0, 0.3);
}

.event-content {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.event-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.event-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.event-text {
  flex: 1;
  font-size: 0.85rem;
  line-height: 1.4;
}

.event-detail {
  font-size: 0.75rem;
  color: var(--muted);
  padding: 0.4rem;
  background: rgba(0, 0, 0, 0.15);
  border-radius: 4px;
  font-family: ui-monospace, monospace;
  word-break: break-all;
  max-height: 100px;
  overflow-y: auto;
}

.event-time {
  font-size: 0.7rem;
  color: var(--muted);
  text-align: right;
}

.tools li.event-plan_start,
.tools li.event-plan_done {
  border-left: 3px solid #c4b5fd;
}

.tools li.event-plan_step {
  border-left: 3px solid #ddd6fe;
  padding-left: 0.6rem;
}

.tools li.event-tool_start,
.tools li.event-tool_end {
  border-left: 3px solid #86efac;
}

.tools li.event-guardrail {
  border-left: 3px solid #fca5a5;
  background: rgba(239, 68, 68, 0.1);
}

.tools li.status-running {
  animation: pulse 1.5s ease-in-out infinite;
}

.tools li.status-success {
  border-left-color: #4ade80;
}

.tools li.status-error {
  border-left-color: #f87171;
  background: rgba(239, 68, 68, 0.1);
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.6;
  }
}
.muted {
  color: var(--muted);
}
@media (max-width: 1200px) {
  .layout {
    grid-template-columns: 220px 1fr 180px;
  }
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
    position: relative;
  }
  
  .sidebar {
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 280px;
    z-index: 100;
    transform: translateX(-100%);
    transition: transform 0.3s ease;
    border-right: 1px solid var(--border);
  }
  
  .sidebar.open {
    transform: translateX(0);
  }
  
  .sidebar-overlay {
    display: none;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 90;
  }
  
  .sidebar-overlay.open {
    display: block;
  }
  
  .main {
    width: 100%;
  }
  
  .tools {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    width: 280px;
    z-index: 100;
    transform: translateX(100%);
    transition: transform 0.3s ease;
    border-left: 1px solid var(--border);
  }
  
  .tools.open {
    transform: translateX(0);
  }
  
  .tools-overlay {
    display: none;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 90;
  }
  
  .tools-overlay.open {
    display: block;
  }
  
  .top {
    padding: 1rem;
  }
  
  .thread {
    padding: 1rem;
  }
  
  .composer {
    padding: 0.75rem 1rem 1rem;
    flex-direction: column;
  }
  
  .send {
    width: 100%;
  }
  
  textarea {
    min-height: 60px;
  }
}

@media (max-width: 640px) {
  .sidebar,
  .tools {
    width: 100%;
  }
  
  .top h2 {
    font-size: 1rem;
  }
  
  .bubble-row {
    max-width: 100%;
  }
  
  .bubble {
    padding: 0.5rem 0.7rem;
    font-size: 0.95rem;
  }
  
  .composer {
    padding: 0.6rem 0.75rem 0.75rem;
  }
  
  textarea {
    font-size: 16px;
  }
}
</style>
