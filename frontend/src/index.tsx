import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

// Radix Colors primitive scales (tier 1). The semantic aliases live in
// src/styles/_tokens.scss and everything else consumes those.
import '@radix-ui/colors/slate.css';
import '@radix-ui/colors/slate-dark.css';
import '@radix-ui/colors/blue.css';
import '@radix-ui/colors/blue-dark.css';
import '@radix-ui/colors/red.css';
import '@radix-ui/colors/red-dark.css';
import '@radix-ui/colors/green.css';
import '@radix-ui/colors/green-dark.css';
import '@radix-ui/colors/amber.css';
import '@radix-ui/colors/amber-dark.css';
import './App.scss';
import {CookiesProvider} from "react-cookie";

const root = document.getElementById('app-root')!;
ReactDOM.createRoot(root).render(<CookiesProvider><App /></CookiesProvider>);