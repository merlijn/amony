export type Theme = 'light' | 'dark';

// Applies a theme to <html>. The rest of the app (and the main frontend) uses
// the `data-theme` attribute, while Radix Colors keys its dark scale off the
// `.dark` class, so we keep both in sync.
export const applyTheme = (theme: Theme) => {
  const root = document.documentElement;
  root.dataset.theme = theme;
  root.classList.toggle('dark', theme === 'dark');
};

// Follows the OS colour-scheme preference. Returns an unsubscribe function.
export const applySystemTheme = () => {
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const update = () => applyTheme(media.matches ? 'dark' : 'light');
  update();
  media.addEventListener('change', update);
  return () => media.removeEventListener('change', update);
};
