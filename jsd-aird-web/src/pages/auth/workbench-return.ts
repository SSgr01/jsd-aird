/** The gateway may return to the native workbench after a document login. */
export function workbenchReturn(search: string, hash = ''): string | undefined {
  const target = new URLSearchParams(search).get('returnTo');
  if (!target?.startsWith('/agent/') || /[\\\s]/u.test(target)) return;
  const url = new URL(target, 'https://workbench.invalid');
  if (url.origin !== 'https://workbench.invalid' || !url.pathname.startsWith('/agent/')) return;
  // A redirect without a fragment inherits the original workbench fragment.
  return url.pathname + url.search + (url.hash || hash);
}
