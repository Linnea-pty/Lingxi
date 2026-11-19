<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError, describeError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')
const fieldError = reactive<{ username: string; password: string }>({ username: '', password: '' })

const canSubmit = computed(() => form.username.trim() !== '' && form.password !== '')

function resetErrors(): void {
  errorMessage.value = ''
  fieldError.username = ''
  fieldError.password = ''
}

async function submit(): Promise<void> {
  resetErrors()
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    if (error instanceof ApiError) {
      // 表单级错误落到对应输入框，其余给整体提示
      if (error.code === 'USER_LOGIN_FAILED') {
        fieldError.password = '用户名或密码错误'
        return
      }
      if (error.code === 'USER_BANNED') {
        // 封禁不重试，清掉本地登录态
        auth.clearSession()
        errorMessage.value = '账号已被封禁，请联系管理员'
        return
      }
    }
    errorMessage.value = describeError(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card card">
      <div class="auth-brand">
        <span class="auth-logo">灵犀</span>
        <p class="auth-tagline">和有意思的人，聊有意思的事</p>
      </div>

      <h1 class="auth-title">登录</h1>

      <p v-if="errorMessage" class="alert alert--error">{{ errorMessage }}</p>

      <form @submit.prevent="submit">
        <label class="field">
          <span class="field__label">用户名</span>
          <input
            v-model="form.username"
            class="input"
            type="text"
            autocomplete="username"
            placeholder="区分大小写的用户名"
          />
          <span v-if="fieldError.username" class="field__error">{{ fieldError.username }}</span>
        </label>

        <label class="field">
          <span class="field__label">密码</span>
          <input
            v-model="form.password"
            class="input"
            type="password"
            autocomplete="current-password"
            placeholder="8~64 位"
          />
          <span v-if="fieldError.password" class="field__error">{{ fieldError.password }}</span>
        </label>

        <button class="btn btn--block" type="submit" :disabled="!canSubmit || submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="auth-switch">
        还没有账号？
        <RouterLink to="/register">立即注册</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at 20% 10%, rgba(99, 102, 241, 0.14), transparent 45%),
    radial-gradient(circle at 85% 90%, rgba(168, 85, 247, 0.12), transparent 45%),
    var(--bg);
}
.auth-card {
  width: 100%;
  max-width: 400px;
  padding: 32px 28px 26px;
}
.auth-brand {
  margin-bottom: 20px;
}
.auth-logo {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 0.08em;
  background: linear-gradient(120deg, #4f46e5, #a855f7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.auth-tagline {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 2px;
}
.auth-title {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 18px;
}
.auth-switch {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}
</style>
