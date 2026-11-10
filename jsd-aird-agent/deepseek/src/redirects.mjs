/** Resolve file-version redirects against the requested resource, then apply the public mount once. */
export function browserRedirect(location, { origin, prefix, target }) {
  const destination = new URL(location, origin + target);
  if (destination.searchParams.has('token')) throw new Error('INVALID_UPSTREAM_REDIRECT');
  if (destination.origin !== origin) return location;
  const path = destination.pathname.startsWith(prefix) ? destination.pathname : prefix + destination.pathname.slice(1);
  return path + destination.search + destination.hash;
}
