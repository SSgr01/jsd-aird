export function loginDestination(requestedFrom?: string): string {
  return requestedFrom?.split('?')[0] === '/change-password' ? '/assistant' : requestedFrom || '/assistant';
}
