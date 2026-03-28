import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import ChatView from '../views/ChatView.vue'
import AdminKnowledgeView from '../views/AdminKnowledgeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', name: 'chat', component: ChatView },
    { path: '/admin/knowledge', name: 'admin-knowledge', component: AdminKnowledgeView },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
