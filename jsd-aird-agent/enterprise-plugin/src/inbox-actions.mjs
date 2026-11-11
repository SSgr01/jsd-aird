/** Missing queue actions operate only on the native durable Inbox. */
export function updateNativeInbox(agent, request) {
  const queue = agent.inbox.nextTurn;
  if (!Array.isArray(request.expected_ids) || queue.length !== request.expected_ids.length
      || queue.some((item, index) => item.id !== request.expected_ids[index]))
    throw new Error('INBOX_CHANGED');
  const index = queue.findIndex(item => item.id === request.item_id);
  if (index < 0 || queue[index].source.kind !== 'user') throw new Error('INBOX_ITEM_NOT_PENDING');
  if (request.kind === 'move') {
    if (![1, -1].includes(request.direction)) throw new Error('INBOX_DIRECTION_INVALID');
    const target = index + request.direction;
    if (target < 0 || target >= queue.length) throw new Error('INBOX_BOUNDARY');
    const start = Math.min(index, target);
    // One synchronous native splice retains identities, attachments and log replay.
    agent.inbox.splice('next-turn', start, 2, [queue[start + 1], queue[start]]);
    return { accepted: true };
  }
  if (request.kind === 'take') {
    const item = queue[index];
    if (!item.content.every(block => block.type === 'text')) throw new Error('INBOX_TEXT_ONLY');
    const text = item.content.map(block => block.text).join('');
    if (request.expected_text !== text) throw new Error('INBOX_CHANGED');
    agent.inbox.remove(item.id);
    return { accepted: true, text };
  }
  throw new Error('INBOX_ACTION_INVALID');
}
