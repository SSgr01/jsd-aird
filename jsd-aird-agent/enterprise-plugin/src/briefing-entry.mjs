/** Validate only navigation identifiers; authorization remains with Control. */
export function readBriefingEntry(address) {
  const url = new URL(address, 'http://local');
  const kind = url.searchParams.get('briefing');
  const run_id = url.searchParams.get('run');
  if (!['project-morning','research-weekly','experiment-review'].includes(kind)
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(run_id || '')) return null;
  return {kind,run_id};
}
