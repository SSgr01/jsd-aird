import ReactDOM from 'react-dom/client';

import '@/utils/dayjs';
import '@/styles/global.css';
import { useAuthStore } from '@/stores/auth-store';
import { installBuildRecovery } from '@/services/release/build-recovery';
import { markPerformance } from '@/services/performance/metrics';
import { installSessionSync } from '@/app/session-sync';

const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('Root element #root was not found.');
}

// Univer owns nested React roots. React 18 StrictMode's development-only double unmount
// races those roots and causes removeChild/unmount errors, so the application root is
// mounted once while feature tests keep exercising strict lifecycle checks independently.
markPerformance('boot-start');
installBuildRecovery();
installSessionSync();
// Session check and application/route chunks start together. Keep the HTML
// skeleton in place while modules load; AuthGate renders the same geometry.
if (window.location.pathname !== '/login') void useAuthStore.getState().load();
void import('@/app/App').then(({ App }) => ReactDOM.createRoot(rootElement).render(<App />)).catch(() => {
  const message = document.createElement('p');
  message.textContent = '应用加载失败，请检查网络后刷新重试。';
  message.setAttribute('role', 'alert');
  rootElement.append(message);
});
