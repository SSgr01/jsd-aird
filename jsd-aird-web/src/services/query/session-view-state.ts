export const listPositions = new Map<string, { window: number; content: number }>();
export function clearSessionViewState() { listPositions.clear(); }
