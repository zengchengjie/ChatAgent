<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { apiJson, ApiError } from '../api/http'
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

const router = useRouter()
const auth = useAuthStore()

const sessions = ref<SessionRow[]>([])
const activeId = ref<string | null>(null)
const messages = ref<MessageRow[]>([])
const input = ref('')
const busy = ref(false)
const banner = ref('')
/** 桌面默认展开侧栏；窄屏默认收起，避免遮挡主内容 */
function initialSidebarOpen(): boolean {
  if (typeof window === 'undefined') return true
  return window.innerWidth > 960
}
const sidebarOpen = ref(initialSidebarOpen())
const searchQuery = ref('')
const editingSessionId = ref<string | null>(null)
const editingTitle = ref('')
const showSessionMenu = ref<string | null>(null)

const allowedModels = ref<string[]>([])
const selectedModel = ref<string>('qwen3.5-flash')

const title = computed(() => sessions.value.find((s) => s.id === activeId.value)?.title ?? '对话')

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
  const name = target?.title || '该对话'
  const ok = window.confirm(`确定删除“${name}”吗？此操作不可撤销。`)
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
    banner.value = '重命名失败'
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
    banner.value = '导出失败'
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
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Session-Id': sid,
        Authorization: `Bearer ${auth.token}`,
      },
      body: JSON.stringify({ message: text }),
    })
    if (res.status === 429) {
      banner.value = '请求过于频繁（429），请稍后再试。'
      await reloadThread(sid)
      return
    }
    if (!res.ok) {
      const t = await res.text()
      banner.value = t || `错误 ${res.status}`
      await reloadThread(sid)
      return
    }
    const data = await res.json()
    const answer = data.answer || ''
    
    // 模拟助手回复消息
    messages.value = [
      ...messages.value,
      {
        id: Date.now(),
        role: 'ASSISTANT',
        content: answer,
        toolCallsJson: null,
        toolCallId: null,
        createdAt: new Date().toISOString(),
      },
    ]
    await scrollThreadToBottom()
    await loadSessions()
  } catch (e) {
    if (e instanceof ApiError) {
      banner.value = e.body
    } else {
      banner.value = '请求失败'
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
      return '你'
    case 'ASSISTANT':
      return '助手'
    case 'TOOL':
      return '工具'
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
  <div
    class="layout"
    :class="{ 'sidebar-collapsed': !sidebarOpen }"
  >
    <div class="sidebar-overlay" :class="{ open: sidebarOpen }" @click="sidebarOpen = false"></div>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="brand">Chat Agent</div>
      <div class="model-picker">
        <label class="model-label">模型</label>
        <select v-model="selectedModel" class="model-select">
          <option v-for="m in allowedModels" :key="m" :value="m">{{ m }}</option>
          <option v-if="allowedModels.length === 0" :value="selectedModel">{{ selectedModel }}</option>
        </select>
      </div>
      <button class="new-chat" type="button" @click="createSession">+ 新建对话</button>
      <div class="search-box">
        <input 
          v-model="searchQuery" 
          type="text" 
          placeholder="搜索对话..." 
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
                  重命名
                </button>
                <button type="button" @click="exportSession(s.id); showSessionMenu = null">
                  导出
                </button>
                <button type="button" @click="deleteSession(s.id); showSessionMenu = null">
                  删除
                </button>
              </div>
            </div>
          </template>
        </li>
      </ul>
      <div class="foot">
        <span class="who">{{ auth.username || 'user' }}</span>
        <button type="button" class="link" @click="logout">退出登录</button>
      </div>
    </aside>
    <main class="main">
      <header class="top">
        <div class="header-left">
          <button
            class="menu-btn"
            type="button"
            @click="sidebarOpen = !sidebarOpen"
            aria-label="打开或关闭会话列表"
            :aria-expanded="sidebarOpen"
          >
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="3" y1="6" x2="21" y2="6"></line>
              <line x1="3" y1="12" x2="21" y2="12"></line>
              <line x1="3" y1="18" x2="21" y2="18"></line>
            </svg>
          </button>
          <h2>{{ title }}</h2>
        </div>
        <div class="header-right">
          <span v-if="banner" class="banner">{{ banner }}</span>
        </div>
      </header>
      <div ref="threadEl" class="thread" @scroll="onThreadScroll">
        <div v-for="m in messages" :key="m.id" class="bubble-row" :data-role="m.role.toLowerCase()">
          <div class="meta">{{ roleLabel(m.role) }}</div>
          <div class="bubble" v-html="renderMarkdown(m.content || '')"></div>
        </div>
      </div>
      <div class="composer">
        <textarea
          v-model="input"
          rows="3"
          placeholder="输入消息…（试试网络连不上、VPN 失败等 IT 问题）"
          @keydown.enter.exact.prevent="send"
        />
        <button type="button" class="send" :disabled="busy || !activeId" @click="send">
          {{ busy ? '…' : '发送' }}
        </button>
      </div>
    </main>
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

.btn-secondary {
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.06);
  color: var(--text);
  border-radius: 8px;
  padding: 0.5rem 0.75rem;
  cursor: pointer;
  font-size: 0.85rem;
  transition: all 0.2s ease;
}

.btn-secondary:hover {
  background: rgba(99, 102, 241, 0.1);
  border-color: rgba(99, 102, 241, 0.3);
}

.menu-btn:hover,
.close-btn:hover {
  background: rgba(99, 102, 241, 0.1);
  border-color: rgba(99, 102, 241, 0.3);
}

.sidebar-overlay {
  display: none;
}

.top h2 {
  margin: 0;
  font-size: 1.1rem;
}

@media (max-width: 960px) {
  .sidebar-overlay.open {
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
.muted {
  color: var(--muted);
}
@media (max-width: 1200px) {
  .layout {
    grid-template-columns: 220px 1fr;
  }
}

/* 宽屏：汉堡收起左侧栏 */
@media (min-width: 961px) {
  .layout.sidebar-collapsed {
    grid-template-columns: 0 minmax(0, 1fr);
  }

  .layout.sidebar-collapsed .sidebar {
    min-width: 0;
    overflow: hidden;
    padding-left: 0;
    padding-right: 0;
    border-right-color: transparent;
    opacity: 0;
    pointer-events: none;
  }
}

@media (min-width: 961px) and (max-width: 1200px) {
  .layout.sidebar-collapsed {
    grid-template-columns: 0 minmax(0, 1fr);
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
    pointer-events: none;
  }

  .sidebar.open {
    transform: translateX(0);
    pointer-events: auto;
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
  .sidebar {
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
