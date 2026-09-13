/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 基础路径：开发为空（走 /api 代理），生产为 /api（同源反代）。 */
  readonly VITE_API_BASE: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
