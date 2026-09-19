import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

const backendHost = 'http://127.0.0.1:8182';

export default defineConfig({
    plugins: [react()],
    base: '/',
    publicDir: 'public',
    resolve: {
        alias: {
            'src': path.resolve(__dirname, './src')
        }
    },
    server: {
        port: 5174,
        proxy: {
            '/api': backendHost,
            '/resources': backendHost
        }
    }
});
