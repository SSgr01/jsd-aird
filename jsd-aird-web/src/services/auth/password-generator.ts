const RANDOM_BYTES = 15;

export function generateAdminPassword(): string {
  if (!globalThis.crypto?.getRandomValues) {
    throw new Error('当前浏览器不支持安全随机密码生成');
  }
  const bytes = new Uint8Array(RANDOM_BYTES);
  globalThis.crypto.getRandomValues(bytes);
  let encoded = '';
  for (const byte of bytes) encoded += String.fromCharCode(byte);
  const base64Url = globalThis.btoa(encoded)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
  return `Jsd@${base64Url}`;
}
