import React, { createContext, useContext, useEffect, ReactNode } from 'react';
import { ThemeSetting, usePrefs } from './api/Prefs';
import { resolveTheme, applyTheme, onSystemThemeChange } from './theme';

type ThemeContextType = {
  themeSetting: ThemeSetting;
  setTheme: (setting: ThemeSetting) => void;
};

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export const ThemeProvider = ({ children }: { children: ReactNode }) => {
  const [prefs, updatePrefs] = usePrefs();
  const themeSetting = prefs.theme;

  // Apply theme whenever the setting changes
  useEffect(() => {
    applyTheme(resolveTheme(themeSetting));
  }, [themeSetting]);

  // Listen for system theme changes when using 'system' setting
  useEffect(() => {
    if (themeSetting === 'system') {
      return onSystemThemeChange((systemTheme) => {
        applyTheme(systemTheme);
      });
    }
  }, [themeSetting]);

  const setTheme = (setting: ThemeSetting) => {
    updatePrefs({ theme: setting });
  };

  return (
    <ThemeContext.Provider value={{ themeSetting, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
};
