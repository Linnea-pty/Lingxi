import { ref, shallowRef } from 'vue'
import { describeError } from '@/api/http'

interface CursorResult<T> {
  nextCursor: string | null
  hasMore: boolean
  items: T[]
}

/**
 * 键集游标列表：只认 nextCursor + hasMore，不自己算页码。
 * 下拉刷新调用 loadFirst()，翻页调用 loadMore()。
 */
export function useCursorList<T>(fetcher: (cursor: string | null, size: number) => Promise<CursorResult<T>>, size = 10) {
  const items = shallowRef<T[]>([])
  const nextCursor = ref<string | null>(null)
  const hasMore = ref(false)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref('')
  const loaded = ref(false)
  /**
   * 请求代次：reset() 会自增。旧代次返回的响应直接丢弃，
   * 避免「切筛选条件后慢响应覆盖新结果」这类串页。
   */
  let generation = 0

  async function loadFirst(): Promise<void> {
    if (loading.value) return
    const current = ++generation
    loading.value = true
    error.value = ''
    try {
      const page = await fetcher(null, size)
      if (current !== generation) return
      items.value = page.items
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
      loaded.value = true
    } catch (e) {
      if (current !== generation) return
      error.value = describeError(e)
    } finally {
      if (current === generation) loading.value = false
    }
  }

  async function loadMore(): Promise<void> {
    if (loading.value || loadingMore.value || !hasMore.value || !nextCursor.value) return
    const current = generation
    loadingMore.value = true
    error.value = ''
    try {
      const page = await fetcher(nextCursor.value, size)
      if (current !== generation) return
      items.value = [...items.value, ...page.items]
      nextCursor.value = page.nextCursor
      hasMore.value = page.hasMore
    } catch (e) {
      if (current !== generation) return
      error.value = describeError(e)
    } finally {
      if (current === generation) loadingMore.value = false
    }
  }

  /** 本地局部更新：删除或替换单条后不整页重拉 */
  function update(predicate: (item: T) => boolean, updater: (item: T) => T | null): void {
    const next: T[] = []
    for (const item of items.value) {
      if (!predicate(item)) {
        next.push(item)
        continue
      }
      const patched = updater(item)
      if (patched) next.push(patched)
    }
    items.value = next
  }

  function reset(): void {
    // 作废在飞的请求，并放开 loading，否则紧接着的 loadFirst 会被自身守卫拦掉
    generation += 1
    items.value = []
    nextCursor.value = null
    hasMore.value = false
    loaded.value = false
    error.value = ''
    loading.value = false
    loadingMore.value = false
  }

  return {
    items,
    nextCursor,
    hasMore,
    loading,
    loadingMore,
    error,
    loaded,
    loadFirst,
    loadMore,
    update,
    reset,
  }
}
