export const listPositions = new Map<string, { window: number; content: number; tables?: Record<string, { top: number; left: number }> }>();
export function clearSessionViewState() { listPositions.clear(); }
