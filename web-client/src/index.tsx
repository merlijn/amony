import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

import './App.scss';
import {CookiesProvider} from "react-cookie";

const root = document.getElementById('app-root')!;
ReactDOM.createRoot(root).render(<CookiesProvider><App /></CookiesProvider>);