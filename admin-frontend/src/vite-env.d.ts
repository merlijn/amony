/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OAUTH_PROVIDER?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
