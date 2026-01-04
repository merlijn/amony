// Theme configuration
// Change this value to 'light' or 'dark' to switch themes
export type Theme = 'light' | 'dark';

export const CURRENT_THEME: Theme = 'dark';

export const applyTheme = (theme: Theme) => {
  document.documentElement.setAttribute('data-theme', theme);
};
