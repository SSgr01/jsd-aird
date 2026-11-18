/** Keep admitted input stable while refreshing the latest business state. */
export function mergeBusinessContext(previous, current) {
  if (!previous) return current;
  const before = JSON.parse(previous), after = JSON.parse(current);
  if (Object.keys(after).every(key => sameValue(after[key], before[key]))) return previous;
  return JSON.stringify({ ...before, ...after });
}

// Host JSON object ordering is not a business-state change. Arrays remain ordered.
function sameValue(left, right) {
  if (left === right) return true;
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false;
  if (Array.isArray(left) !== Array.isArray(right)) return false;
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length && keys.every(key =>
    Object.hasOwn(right, key) && sameValue(left[key], right[key]));
}
