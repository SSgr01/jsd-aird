/** Read bodies overlap; the native scheduler retains ordered guards and result commits. */
const parallelReads = new Set([
  'knowledge_search', 'document_outline', 'document_read', 'table_read',
  'evidence_read', 'evidence_context', 'evidence_image_read',
  'private_document_search', 'private_document_outline', 'private_document_read',
]);

export function readExecutionPolicy(name) {
  return {isConcurrencySafe: () => parallelReads.has(name)};
}
