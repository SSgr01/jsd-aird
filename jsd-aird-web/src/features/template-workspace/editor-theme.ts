import { defaultTheme, type Theme } from '@univerjs/presets';

// Theme the editor chrome without changing stored cell or document styles.
export const editorTheme: Theme = {
  ...defaultTheme,
  primary: {
    50: '#faf7f8',
    100: '#f4e9ed',
    200: '#ecdce3',
    300: '#dfc4cf',
    400: '#c29aaa',
    500: '#a97e8f',
    600: '#8f5f70',
    700: '#805263',
    800: '#704556',
    900: '#603a49',
  },
  gray: {
    0: '#ffffff',
    50: '#faf7f8',
    100: '#f6f1f3',
    200: '#eee7ea',
    300: '#e8dfe3',
    400: '#cdbec5',
    500: '#71656c',
    600: '#62585e',
    700: '#51474d',
    800: '#40373c',
    900: '#302b2e',
    1000: '#211c1f',
  },
};
