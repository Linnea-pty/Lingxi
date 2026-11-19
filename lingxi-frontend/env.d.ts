/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 接口根路径，缺省 http://localhost:18080/api/v1 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
