import { ref } from 'vue'
import { defineStore } from 'pinia'
import { notificationApi } from '@/api/notification'

/** 通知角标：只存服务端返回的数字，本地永不自行加减 */
export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)

  async function refreshUnreadCount(): Promise<void> {
    try {
      const data = await notificationApi.unreadCount()
      unreadCount.value = data.unreadCount
    } catch {
      // 角标失败静默处理，不打断主流程
    }
  }

  function setUnreadCount(count: number): void {
    unreadCount.value = count
  }

  function reset(): void {
    unreadCount.value = 0
  }

  return { unreadCount, refreshUnreadCount, setUnreadCount, reset }
})
