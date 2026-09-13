<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiRequestError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ mode: 'login' | 'register' }>()
const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const isRegister = computed(() => props.mode === 'register')

async function submit() {
  error.value = ''
  try {
    if (isRegister.value) await auth.register(username.value, password.value, confirmPassword.value)
    else await auth.login(username.value, password.value)
    await router.replace('/')
  } catch (cause) {
    error.value = cause instanceof ApiRequestError || cause instanceof Error ? cause.message : '操作失败，请重试'
  }
}
</script>

<template>
  <main class="auth-shell">
    <section class="auth-card">
      <header class="auth-brand">词表速筛</header>
      <div class="auth-heading">
        <p class="auth-kicker">VocabTrim Full</p>
        <h1>{{ isRegister ? '注册' : '登录' }}</h1>
      </div>
      <form class="auth-form" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model.trim="username" name="username" autocomplete="username" minlength="3" maxlength="32" required autofocus />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" name="password" type="password" :autocomplete="isRegister ? 'new-password' : 'current-password'" minlength="8" maxlength="72" required />
        </label>
        <label v-if="isRegister">
          <span>确认密码</span>
          <input v-model="confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required />
        </label>
        <p v-if="error" class="auth-error">{{ error }}</p>
        <button class="auth-submit" type="submit" :disabled="auth.busy">{{ auth.busy ? '请稍候' : (isRegister ? '注册并登录' : '登录') }}</button>
      </form>
      <p class="auth-switch">
        {{ isRegister ? '已经有账号？' : '还没有账号？' }}
        <RouterLink :to="isRegister ? '/login' : '/register'">{{ isRegister ? '登录' : '注册' }}</RouterLink>
      </p>
    </section>
  </main>
</template>
