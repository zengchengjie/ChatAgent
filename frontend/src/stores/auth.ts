import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const TOKEN_KEY = 'chatagent_jwt'
const USERNAME_KEY = 'chatagent_username'

function parseJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.')
    if (parts.length < 2) return null
    // JWT 用 base64url 编码：- → +, _ → /，并补齐 padding
    let base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const pad = base64.length % 4
    if (pad === 2) base64 += '=='
    else if (pad === 3) base64 += '='
    return JSON.parse(atob(base64))
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const storedToken = sessionStorage.getItem(TOKEN_KEY)
  const storedUsername = sessionStorage.getItem(USERNAME_KEY)
  // 优先用存储的 username，否则从 token 解码
  const _initialUsername = storedUsername ?? parseJwtPayload(storedToken ?? '')?.sub as string | null
  const token = ref<string | null>(storedToken)
  const username = ref<string | null>(_initialUsername)


  const isAuthenticated = computed(() => !!token.value)

  function setSession(jwt: string, user?: string) {
    token.value = jwt
    sessionStorage.setItem(TOKEN_KEY, jwt)
    if (user) {
      username.value = user
      sessionStorage.setItem(USERNAME_KEY, user)
    }
  }

  function clear() {
    token.value = null
    username.value = null
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(USERNAME_KEY)
  }

  return { token, username, isAuthenticated, setSession, clear }
})
