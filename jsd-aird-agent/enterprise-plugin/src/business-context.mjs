/** Keep admitted input stable while refreshing the latest business state. */
export function mergeBusinessContext(previous, current) {
  if (!previous) return current;
  const before = JSON.parse(previous), after = JSON.parse(current);
  if (Object.keys(after).every(key => JSON.stringify(after[key]) === JSON.stringify(before[key]))) return previous;
  return JSON.stringify({ ...before, ...after });
}
