import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import viteCompression from 'vite-plugin-compression'

export default defineConfig({
    plugins: [
        react(),
        viteCompression({
            algorithm: 'brotliCompress',
            ext: '.br',
        }),
        viteCompression({
            algorithm: 'gzip',
            ext: '.gz',
        })
    ],
    base: '/',
    publicDir: "public",
    environment: 'jsdom',
    css: {
        preprocessorOptions: {
            scss: {
                // api: 'modern-compiler', // or "modern"
                silenceDeprecations: ["legacy-js-api"],
            }
        }
    },
    coverage: {
        reporter: ['text', 'json', 'html'],
        include: ['src/**/*'],
        exclude: [],
    },
    server: {
        proxy: {
            '/api': 'http://127.0.0.1:8080',
            '/resources': 'http://127.0.0.1:8080',
            '/login': 'http://127.0.0.1:8080'
        }
    }
});