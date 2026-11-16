import { defaultTheme, type Theme } from '@univerjs/presets';

// Theme the editor chrome without changing stored cell or document styles.
export const editorTheme: Theme = {
  ...defaultTheme,
  primary: {
    50: '#fafafa',
    100: '#f0f0f0',
    200: '#e5e5e5',
    300: '#d4d4d4',
    400: '#a3a3a3',
    500: '#737373',
    600: '#262626',
    700: '#404040',
    800: '#262626',
    900: '#171717',
  },
  gray: {
    0: '#ffffff',
    50: '#fafafa',
    100: '#f5f5f5',
    200: '#ededed',
    300: '#e5e5e5',
    400: '#d4d4d4',
    500: '#000000',
    600: '#000000',
    700: '#000000',
    800: '#000000',
    900: '#000000',
    1000: '#000000',
  },
};
