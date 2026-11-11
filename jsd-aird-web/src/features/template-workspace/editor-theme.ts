import { defaultTheme, type Theme } from '@univerjs/presets';

// Theme the editor chrome without changing stored cell or document styles.
export const editorTheme: Theme = {
  ...defaultTheme,
  primary: {
    50: '#f7faff',
    100: '#edf3fe',
    200: '#cdddf8',
    300: '#aec9f2',
    400: '#7fa8e8',
    500: '#578bd9',
    600: '#356fc8',
    700: '#2c5eac',
    800: '#264f8d',
    900: '#203f70',
  },
  gray: {
    0: '#ffffff',
    50: '#f8f9fb',
    100: '#f7f8fa',
    200: '#eef0f3',
    300: '#e8ebf0',
    400: '#d8dee8',
    500: '#646a73',
    600: '#646a73',
    700: '#4c535e',
    800: '#333a45',
    900: '#1f2329',
    1000: '#1f2329',
  },
};
