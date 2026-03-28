<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ApiError, apiJson } from '../api/http'
import { useAuthStore } from '../stores/auth'

type Doc = {
  id: number
  docTitle: string
  sourcePath: string
  version: number | null
  docHash: string | null
  createdAt: string | null
  updatedAt: string | null
  chunkCount: number
}

const docs = ref<Doc[]>([])
type EvalRun = {
  id: string
  k: number
  minScore: number
  caseCount: number
  hits: number
  recallAtK: number
  mrr: number
  avgLatencyMs: number
  createdAt: string
}
const evalRuns = ref<EvalRun[]>([])
const loading = ref(false)
const err = ref<string | null>(null)

const file = ref<File | null>(null)
const title = ref('')

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  file.value = input.files?.[0] ?? null
}

async function refresh() {
  loading.value = true
  err.value = null
  try {
    docs.value = await apiJson<Doc[]>('/api/admin/knowledge/docs')
    evalRuns.value = await apiJson<EvalRun[]>('/api/admin/knowledge/eval/runs?limit=20')
  } catch (e) {
    if (e instanceof ApiError) {
      err.value = `${e.status}: ${e.body || e.message}`
    } else {
      err.value = String(e)
    }
  } finally {
    loading.value = false
  }
}

async function upload() {
  if (!file.value) return
  loading.value = true
  err.value = null
  try {
    const auth = useAuthStore()
    const fd = new FormData()
    fd.append('file', file.value)
    if (title.value.trim()) fd.append('title', title.value.trim())
    const res = await fetch('/api/admin/knowledge/docs', {
      method: 'POST',
      headers: auth.token ? { Authorization: `Bearer ${auth.token}` } : undefined,
      body: fd,
    })
    const text = await res.text()
    if (!res.ok) throw new ApiError(res.status, text)
    file.value = null
    title.value = ''
    await refresh()
  } catch (e) {
    if (e instanceof ApiError) {
      err.value = `${e.status}: ${e.body || e.message}`
    } else {
      err.value = String(e)
    }
  } finally {
    loading.value = false
  }
}

async function reindex(docId: number) {
  loading.value = true
  err.value = null
  try {
    await apiJson<void>(`/api/admin/knowledge/docs/${docId}/reindex`, { method: 'POST' })
    await refresh()
  } catch (e) {
    if (e instanceof ApiError) {
      err.value = `${e.status}: ${e.body || e.message}`
    } else {
      err.value = String(e)
    }
  } finally {
    loading.value = false
  }
}

async function rollback(docId: number) {
  const toVersionRaw = window.prompt('回滚到版本号（数字）：')
  if (!toVersionRaw) return
  const toVersion = Number(toVersionRaw)
  if (!Number.isFinite(toVersion)) return
  loading.value = true
  err.value = null
  try {
    await apiJson<void>(`/api/admin/knowledge/docs/${docId}/rollback?toVersion=${encodeURIComponent(String(toVersion))}`, {
      method: 'POST',
    })
    await refresh()
  } catch (e) {
    if (e instanceof ApiError) {
      err.value = `${e.status}: ${e.body || e.message}`
    } else {
      err.value = String(e)
    }
  } finally {
    loading.value = false
  }
}

async function runEval() {
  loading.value = true
  err.value = null
  try {
    await apiJson<EvalRun>('/api/admin/knowledge/eval/run?k=5&minScore=0.0', { method: 'POST' })
    await refresh()
  } catch (e) {
    if (e instanceof ApiError) {
      err.value = `${e.status}: ${e.body || e.message}`
    } else {
      err.value = String(e)
    }
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="page">
    <header class="top">
      <h2>知识库管理</h2>
      <button class="btn" @click="refresh" :disabled="loading">刷新</button>
    </header>

    <div v-if="err" class="error">{{ err }}</div>

    <section class="card">
      <h3>上传文档</h3>
      <div class="row">
        <input type="file" accept=".md,.txt" @change="onFileChange" />
        <input v-model="title" placeholder="标题（可选）" />
        <button class="btn" @click="upload" :disabled="loading || !file">上传</button>
      </div>
    </section>

    <section class="card">
      <h3>文档列表</h3>
      <div v-if="loading && docs.length === 0" class="muted">加载中…</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>标题</th>
            <th>版本</th>
            <th>分块数</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in docs" :key="d.id">
            <td class="mono">{{ d.id }}</td>
            <td>{{ d.docTitle }}</td>
            <td class="mono">{{ d.version ?? '-' }}</td>
            <td class="mono">{{ d.chunkCount }}</td>
            <td class="mono">{{ d.updatedAt ?? '-' }}</td>
            <td class="actions">
              <button class="btn" @click="reindex(d.id)" :disabled="loading">重建索引</button>
              <button class="btn" @click="rollback(d.id)" :disabled="loading">回滚版本</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card">
      <header class="sectionTop">
        <h3>RAG 评估</h3>
        <button class="btn" @click="runEval" :disabled="loading">运行评估</button>
      </header>
      <table class="table" v-if="evalRuns.length">
        <thead>
          <tr>
            <th>时间</th>
            <th>用例</th>
            <th>Recall@k</th>
            <th>MRR</th>
            <th>延迟（ms）</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in evalRuns" :key="r.id">
            <td class="mono">{{ r.createdAt }}</td>
            <td class="mono">{{ r.hits }}/{{ r.caseCount }}</td>
            <td class="mono">{{ (r.recallAtK * 100).toFixed(1) }}% (k={{ r.k }})</td>
            <td class="mono">{{ r.mrr.toFixed(3) }}</td>
            <td class="mono">{{ r.avgLatencyMs.toFixed(1) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="muted">暂无评估记录。</div>
    </section>
  </div>
</template>

<style scoped>
.page {
  max-width: 980px;
  margin: 0 auto;
  padding: 20px;
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 16px;
  margin-top: 16px;
}
.row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.btn {
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  color: inherit;
  cursor: pointer;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.error {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 60, 60, 0.35);
  background: rgba(255, 60, 60, 0.12);
}
.muted {
  opacity: 0.75;
}
.table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
}
.table th,
.table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New',
    monospace;
  font-size: 12px;
}
.actions {
  display: flex;
  gap: 8px;
}
.sectionTop {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>

