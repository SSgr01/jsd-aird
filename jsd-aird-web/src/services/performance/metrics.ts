let routeDataRecorded = false;
let activeOperations = 0;
export function operationMetricActive() { return activeOperations > 0; }

/** A button is finished after React has committed its unlocked state and painted. */
export function startOperationMetric(operation: string) {
  const startedAt = performance.now();
  activeOperations++;
  let finished = false;
  return () => {
    if (finished) return;
    finished = true;
    activeOperations--;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const name = `aird:operation:${operation}`;
      if (performance.getEntriesByName(name).length >= 30) performance.clearMeasures(name);
      performance.measure(name, { start: startedAt, end: performance.now() });
    }));
  };
}

export function measureProcessing<T>(phase: string, work: () => T): T {
  const start = performance.now();
  try { return work(); }
  finally {
    const name = `aird:processing:${phase}`;
    if (performance.getEntriesByName(name).length >= 30) performance.clearMeasures(name);
    performance.measure(name, { start, end: performance.now() });
  }
}

/** Names only: no URL parameters, document IDs, credentials or document content. */
export function markPerformance(name: string) {
  if (name === 'route-start') routeDataRecorded = false;
  if (typeof performance.mark !== 'function') return;
  performance.clearMarks(`aird:${name}`);
  performance.mark(`aird:${name}`);
}

/** Background polling and writes must not overwrite the navigation sample. */
export function markRouteDataReady() {
  if (routeDataRecorded) return;
  routeDataRecorded = true;
  markPerformance('route-data-ready');
  measurePerformance('route-data', 'route-start', 'route-data-ready');
  if (!performance.getEntriesByName('aird:boot-ready').length) {
    markPerformance('boot-ready');
    measurePerformance('boot', 'boot-start', 'boot-ready');
  }
}

export function measurePerformance(name: string, start: string, end: string) {
  if (typeof performance.measure !== 'function' || !performance.getEntriesByName(`aird:${start}`).length) return;
  performance.clearMeasures(`aird:${name}`);
  performance.measure(`aird:${name}`, `aird:${start}`, `aird:${end}`);
}

export function recordRequestMetric(requestId: string, domain: string, method: string, startedAt: number, status?: number) {
  if (typeof performance.measure !== 'function') return;
  const name = 'aird:http';
  if (performance.getEntriesByName(name).length >= 100) performance.clearMeasures(name);
  performance.measure(name, { start: startedAt, end: performance.now(), detail: { requestId, domain, method, status } });
}
