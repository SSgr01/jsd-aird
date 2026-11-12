import { defaultTheme, type Theme } from '@univerjs/presets';

// Theme the editor chrome without changing stored cell or document styles.
export const editorTheme: Theme = {
  ...defaultTheme,
  primary: {
    50: '#f8fbfe',
    100: '#edf5fc',
    200: '#d1eaf9',
    300: '#b5e1eb',
    400: '#8aadc4',
    500: '#527ca2',
    600: '#315f91',
    700: '#294f79',
    800: '#263548',
    900: '#1f2d3d',
  },
  gray: {
    0: '#ffffff',
    50: '#f8fbfe',
    100: '#f6fafd',
    200: '#edf2f7',
    300: '#e8eef4',
    400: '#dce7ef',
    500: '#000000',
    600: '#000000',
    700: '#000000',
    800: '#000000',
    900: '#000000',
    1000: '#000000',
  },
};
