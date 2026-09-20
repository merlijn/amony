import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import viteCompression from 'vite-plugin-compression'
import path from 'path';

const backendHost  = 'http://127.0.0.1:8182';

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
    resolve: {
        alias: {
            'src': path.resolve(__dirname, './src')
        }
    },
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
            '/api': {
                target: backendHost,
                changeOrigin: true,
                // Mirror the production reverse proxy: forward the origin the browser actually
                // used, so the backend derives the right OAuth redirect_uri instead of the proxy target.
                configure: (proxy) => {
                    proxy.on('proxyReq', (proxyReq, req) => {
                        if (req.headers.host) proxyReq.setHeader('X-Forwarded-Host', req.headers.host);
                        proxyReq.setHeader('X-Forwarded-Proto', 'http');
                    });
                }
            },
            '/resources': backendHost
        }
    }
});