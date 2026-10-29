import { createBrowserRouter } from 'react-router-dom';

import { routeConfig } from '@/routes/route-config';
import { markPerformance } from '@/services/performance/metrics';

export const router = createBrowserRouter(routeConfig, {
  future: {
    v7_relativeSplatPath: true,
  },
});

let locationKey = router.state.location.key;
markPerformance('route-start');
router.subscribe(state => {
  if (state.location.key !== locationKey) {
    locationKey = state.location.key;
    markPerformance('route-start');
  }
});
