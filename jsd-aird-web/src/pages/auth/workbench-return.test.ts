import { describe, expect, it } from 'vitest';
import { workbenchReturn } from './workbench-return';

describe('native workbench login return', () => {
  it('restores the native document with its query and original fragment', () => {
    expect(workbenchReturn('?returnTo=%2Fagent%2F%3Fsession%3Dstopped', '#message-42'))
      .toBe('/agent/?session=stopped#message-42');
  });

  it('retains a fragment already contained in the destination', () => {
    expect(workbenchReturn('?returnTo=' + encodeURIComponent('/agent/#session-1'), '#other'))
      .toBe('/agent/#session-1');
  });

  it.each([
    '', '?returnTo=https://other.invalid/agent/', '?returnTo=//other.invalid/agent/',
    '?returnTo=/agent/../../login', '?returnTo=/agent/%2e%2e/login',
    '?returnTo=/agent/\\other.invalid', '?returnTo=/agent/\nother',
    '?returnTo=/login', '?returnTo=/api/v1/auth/logout', '?returnTo=/agent-malicious/',
  ])('does not navigate outside the workbench: %s', (search) => {
    expect(workbenchReturn(search)).toBeUndefined();
  });
});
