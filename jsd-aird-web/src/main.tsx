import ReactDOM from 'react-dom/client';

import '@/utils/dayjs';
import { App } from '@/app/App';
import '@/styles/global.css';

const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('Root element #root was not found.');
}

// Univer owns nested React roots. React 18 StrictMode's development-only double unmount
// races those roots and causes removeChild/unmount errors, so the application root is
// mounted once while feature tests keep exercising strict lifecycle checks independently.
ReactDOM.createRoot(rootElement).render(<App />);
