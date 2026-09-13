import { defineStore } from 'pinia'
import * as api from '@/api/client'
import type { UserView } from '@/types/vocab'

export const useAuthStore = defineStore('auth', {
  state: () => ({ initialized: false, user: null as UserView | null, csrfToken: '', busy: false }),
  getters: { authenticated: (state) => Boolean(state.user) },
  actions: {
    async refresh() {
      const state = await api.getAuthState()
      this.user = state.user
      this.csrfToken = state.csrfToken
      this.initialized = true
    },
    async ensureInitialized() { if (!this.initialized) await this.refresh() },
    async login(username: string, password: string) {
      this.busy = true
      try {
        if (!this.csrfToken) await this.refresh()
        await api.login(username, password, this.csrfToken)
        await this.refresh()
      } finally { this.busy = false }
    },
    async register(username: string, password: string, confirmPassword: string) {
      this.busy = true
      try {
        if (!this.csrfToken) await this.refresh()
        await api.register(username, password, confirmPassword, this.csrfToken)
        await api.login(username, password, this.csrfToken)
        await this.refresh()
      } finally { this.busy = false }
    },
    async logout() {
      this.busy = true
      try { await api.logout(this.csrfToken) }
      finally {
        this.user = null
        this.busy = false
        try { await this.refresh() } catch { this.initialized = false; this.csrfToken = '' }
      }
    },
  },
})
