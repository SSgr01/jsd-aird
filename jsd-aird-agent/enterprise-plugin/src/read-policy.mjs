/** Read bodies overlap; the native scheduler retains ordered guards and result commits. */
const parallelReads = new Set([
  'business_search', 'business_read', 'business_read_many', 'business_links', 'business_query',
  'knowledge_search', 'document_outline', 'document_read', 'table_read',
  'evidence_read', 'evidence_context', 'evidence_image_read',
  'private_document_search', 'private_document_outline', 'private_document_read',
]);

export function readExecutionPolicy(name) {
  // An implicit batch depends on the previous call's committed read cursor.
  // Explicit fixed-ID batches remain independent and may overlap.
  return {isConcurrencySafe: args => parallelReads.has(name) && (name !== 'business_read_many'
    || Array.isArray(args?.requests) && args.requests.length > 0)};
}
