import React from 'react';
import ReactDOM from 'react-dom/client';
import {CookiesProvider} from 'react-cookie';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import App from './App';
import {applySystemTheme} from './theme';

// Radix Colors primitive scales (tier 1). The semantic aliases live in
// src/styles/_tokens.scss and everything else consumes those.
import '@radix-ui/colors/gray.css';
import '@radix-ui/colors/gray-dark.css';
import '@radix-ui/colors/blue.css';
import '@radix-ui/colors/blue-dark.css';
import './App.scss';

applySystemTheme();

const queryClient = new QueryClient();

const root = document.getElementById('root')!;
ReactDOM.createRoot(root).render(
  <CookiesProvider>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </CookiesProvider>
);
