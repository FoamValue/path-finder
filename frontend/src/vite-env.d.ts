/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 系统版权信息（页面底部展示），可配置 */
  readonly VITE_COPYRIGHT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
