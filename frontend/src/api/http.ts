import { useAuthStore } from '../stores/auth'
import router from '../router'

export class ApiError extends Error {
  status: number
  body: string
  constructor(status: number, body: string) {
    super(body || `HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

export async function apiJson<T>(
  path: string,
  init: RequestInit & { token?: string | null } = {},
): Promise<T> {
  const auth = useAuthStore()
  const tok = init.token ?? auth.token
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init.headers as Record<string, string>),
  }
  if (init.body && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
  }
  if (tok) {
    headers['Authorization'] = `Bearer ${tok}`
  }
  const res = await fetch(path, { ...init, headers })
  const text = await res.text()
  if (res.status === 401) {
    auth.clear()
    if (!path.startsWith('/api/auth/login')) {
      router.replace({ name: 'login', query: { redirect: window.location.pathname + window.location.search } })
    }
  }
  if (!res.ok) {
    throw new ApiError(res.status, text)
  }
  if (!text) {
    return undefined as T
  }
  return JSON.parse(text) as T
}
