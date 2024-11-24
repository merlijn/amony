import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
    plugins: [react()],
    base: '/',
    publicDir: "public",
    environment: 'jsdom',
    css: true,
    coverage: {
        reporter: ['text', 'json', 'html'],
        include: ['src/**/*'],
        exclude: [],
    },
    server: {
        proxy: {
            '/api': 'http://127.0.0.1:8080',
            '/resources': 'http://127.0.0.1:8080'
        }
    }
});