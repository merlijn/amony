import { ThemeSetting } from "./api/Model";

// Theme configuration
export type Theme = 'light' | 'dark';

// Get the system preference for dark/light mode
export const getSystemTheme = (): Theme => {
  if (typeof window !== 'undefined' && window.matchMedia) {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return 'dark'; // Default fallback
};

// Resolve the actual theme based on the setting
export const resolveTheme = (setting: ThemeSetting): Theme => {
  if (setting === 'system') {
    return getSystemTheme();
  }
  return setting;
};

// Apply the theme to the document
export const applyTheme = (theme: Theme) => {
  document.documentElement.setAttribute('data-theme', theme);
};

// Listen for system theme changes (returns cleanup function)
export const onSystemThemeChange = (callback: (theme: Theme) => void): (() => void) => {
  if (typeof window !== 'undefined' && window.matchMedia) {
    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => {
      callback(e.matches ? 'dark' : 'light');
    };
    mediaQuery.addEventListener('change', handler);
    return () => mediaQuery.removeEventListener('change', handler);
  }
  return () => {};
};
