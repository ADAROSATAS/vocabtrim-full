import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AuthView from '@/views/AuthView.vue'
import VocabView from '@/views/VocabView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: VocabView, meta: { requiresAuth: true } },
    { path: '/login', name: 'login', component: AuthView, props: { mode: 'login' } },
    { path: '/register', name: 'register', component: AuthView, props: { mode: 'register' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  try { await auth.ensureInitialized() }
  catch { return to.name === 'login' || to.name === 'register' ? true : { name: 'login' } }
  if (to.meta.requiresAuth && !auth.authenticated) return { name: 'login' }
  if ((to.name === 'login' || to.name === 'register') && auth.authenticated) return { name: 'home' }
  return true
})

export default router
