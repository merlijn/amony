/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_IDENTITY_PROVIDER?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
