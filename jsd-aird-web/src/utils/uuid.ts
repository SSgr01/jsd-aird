import { v4 as uuidv4 } from 'uuid';

/** Generates an RFC 4122 v4 UUID in secure and insecure browser contexts. */
export function generateUUID(): string {
  const crypto = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;

  return typeof crypto?.randomUUID === 'function' ? crypto.randomUUID() : uuidv4();
}
