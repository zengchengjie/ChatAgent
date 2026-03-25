import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const TOKEN_KEY = 'chatagent_jwt'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(sessionStorage.getItem(TOKEN_KEY))
  const username = ref<string | null>(null)

  const isAuthenticated = computed(() => !!token.value)

  function setSession(jwt: string, user?: string) {
    token.value = jwt
    sessionStorage.setItem(TOKEN_KEY, jwt)
    if (user) username.value = user
  }

  function clear() {
    token.value = null
    username.value = null
    sessionStorage.removeItem(TOKEN_KEY)
  }

  return { token, username, isAuthenticated, setSession, clear }
})
