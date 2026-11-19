import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { onUnauthorized } from './api/token'
import { useAuthStore } from './stores/auth'
import { useNotificationStore } from './stores/notification'
import './assets/main.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// 令牌彻底失效（刷新也失败）时统一清登录态并回登录页
onUnauthorized(() => {
  useAuthStore(pinia).clearSession()
  useNotificationStore(pinia).reset()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')
