import { createElement, lazy, type ComponentType } from 'react';
import { matchRoutes } from 'react-router-dom';

const routes = new Map<string, () => Promise<unknown>>();
const resets = new Set<() => void>();

export function routePage<P extends object>(importer: () => Promise<{ default: ComponentType<P> }>) {
  let promise: Promise<{ default: ComponentType<P> }> | undefined;
  let failed = false;
  const preload = () => promise ??= importer().catch((error: unknown) => { failed = true; throw error; });
  let Page = lazy(preload);
  resets.add(() => { if (failed) { promise = undefined; failed = false; Page = lazy(preload); } });
  // The wrapper is stable; retries replace only a failed lazy component.
  const RoutePage = (props: P) => createElement(Page as unknown as ComponentType<P>, props);
  return Object.assign(RoutePage, { preload });
}

export function registerRoutePreload(path: string, preload: () => Promise<unknown>) { routes.set(path, preload); }
export function preloadRoute(path: string) {
  const match = matchRoutes([...routes.keys()].map(path => ({ path })), path)?.[0];
  return match?.route.path ? routes.get(match.route.path)!() : Promise.resolve();
}
export function resetFailedPages() { resets.forEach((reset) => reset()); }
