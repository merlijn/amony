import React from 'react';
import ReactDOM from 'react-dom/client';
import {CookiesProvider} from 'react-cookie';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import App from './App';

import './App.scss';

const queryClient = new QueryClient();

const root = document.getElementById('root')!;
ReactDOM.createRoot(root).render(
  <CookiesProvider>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </CookiesProvider>
);
