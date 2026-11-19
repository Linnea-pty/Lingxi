<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ApiError, describeError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const form = reactive({ username: '', password: '', confirm: '', nickname: '' })
const submitting = ref(false)
const errorMessage = ref('')
const fieldError = reactive<{ username: string; password: string; confirm: string; nickname: string }>(
  { username: '', password: '', confirm: '', nickname: '' },
)

const usernameValid = computed(() => /^[A-Za-z0-9_]{4,32}$/.test(form.username.trim()))
const passwordValid = computed(() => form.password.length >= 8 && form.password.length <= 64)
const nicknameValid = computed(() => form.nickname.trim().length <= 64)
const canSubmit = computed(
  () =>
    usernameValid.value &&
    passwordValid.value &&
    form.confirm === form.password &&
    nicknameValid.value,
)

function resetErrors(): void {
  errorMessage.value = ''
  fieldError.username = ''
  fieldError.password = ''
  fieldError.confirm = ''
  fieldError.nickname = ''
}

async function submit(): Promise<void> {
  resetErrors()
  if (submitting.value) return
  if (!usernameValid.value) {
    fieldError.username = '4~32 位字母、数字或下划线'
    return
  }
  if (!passwordValid.value) {
    fieldError.password = '密码长度需为 8~64 位'
    return
  }
  if (form.confirm !== form.password) {
    fieldError.confirm = '两次输入的密码不一致'
    return
  }
  submitting.value = true
  try {
    // 注册即登录，服务端直接下发令牌
    await auth.register(form.username.trim(), form.password, form.nickname.trim())
    await router.replace('/')
  } catch (error) {
    if (error instanceof ApiError && error.code === 'USER_NAME_DUPLICATED') {
      fieldError.username = '该用户名已被占用'
      return
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
        <p class="auth-tagline">注册后立即可发布动态</p>
      </div>

      <h1 class="auth-title">创建账号</h1>

      <p v-if="errorMessage" class="alert alert--error">{{ errorMessage }}</p>

      <form @submit.prevent="submit">
        <label class="field">
          <span class="field__label">用户名</span>
          <input
            v-model="form.username"
            class="input"
            type="text"
            autocomplete="username"
            placeholder="4~32 位字母 / 数字 / 下划线"
          />
          <span v-if="fieldError.username" class="field__error">{{ fieldError.username }}</span>
          <span v-else class="field__hint">区分大小写，注册后不可修改</span>
        </label>

        <label class="field">
          <span class="field__label">密码</span>
          <input
            v-model="form.password"
            class="input"
            type="password"
            autocomplete="new-password"
            placeholder="8~64 位"
          />
          <span v-if="fieldError.password" class="field__error">{{ fieldError.password }}</span>
        </label>

        <label class="field">
          <span class="field__label">确认密码</span>
          <input
            v-model="form.confirm"
            class="input"
            type="password"
            autocomplete="new-password"
            placeholder="再输入一次密码"
          />
          <span v-if="fieldError.confirm" class="field__error">{{ fieldError.confirm }}</span>
        </label>

        <label class="field">
          <span class="field__label">昵称（选填）</span>
          <input
            v-model="form.nickname"
            class="input"
            type="text"
            maxlength="64"
            placeholder="不填则使用用户名"
          />
          <span v-if="fieldError.nickname" class="field__error">{{ fieldError.nickname }}</span>
        </label>

        <button class="btn btn--block" type="submit" :disabled="!canSubmit || submitting">
          {{ submitting ? '注册中…' : '注册并登录' }}
        </button>
      </form>

      <p class="auth-switch">
        已有账号？
        <RouterLink to="/login">去登录</RouterLink>
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
