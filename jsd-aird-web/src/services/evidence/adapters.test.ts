import { groupEvidence } from './adapters';
import type { SourceRef } from './protocol';
import fixture from '../../../../jsd-aird-agent/contracts/evidence.json';

it('groups paragraphs by immutable file identity while retaining every citation', () => {
  const source = fixture.cases[0]!.source as SourceRef;
  const passages = [
    { source, text: 'first' },
    { source: { ...source, source_node_key: 'next', content_sha256: 'other' }, text: 'second' },
    { source: { ...source, version_id: 'new-version' }, text: 'revised' },
    { source: { ...source, resource_id: 'different-permission-scope' }, text: 'other' },
  ];
  const groups = groupEvidence(passages);
  expect(groups).toHaveLength(3);
  expect(groups[0]!.passages).toEqual(passages.slice(0, 2));
  expect(groups[1]!.source.version_id).toBe('new-version');
});
