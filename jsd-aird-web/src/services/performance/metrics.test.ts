import { performance as nodePerformance } from 'node:perf_hooks';
import { markPerformance, markRouteDataReady } from './metrics';

it('records the first useful route data without replacing it during a later poll or save', () => {
  vi.stubGlobal('performance',nodePerformance);
  try {
    markPerformance('boot-start'); markPerformance('route-start'); markRouteDataReady();
    const first=performance.getEntriesByName('aird:route-data')[0];
    markRouteDataReady(); expect(performance.getEntriesByName('aird:route-data')[0]).toBe(first);
    markPerformance('route-start'); markRouteDataReady();
    expect(performance.getEntriesByName('aird:route-data')[0]).not.toBe(first);
    expect(performance.getEntriesByName('aird:boot')).toHaveLength(1);
  } finally { performance.clearMarks(); performance.clearMeasures(); vi.unstubAllGlobals(); }
});
