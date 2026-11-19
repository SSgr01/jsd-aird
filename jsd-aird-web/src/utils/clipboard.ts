/** Clipboard API requires HTTPS; the selection fallback also works on LAN HTTP. */
export async function copyText(text: string): Promise<void> {
  if (typeof navigator.clipboard?.writeText === 'function') {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch { /* Try the user-initiated selection copy when permission is denied. */ }
  }
  const focused = document.activeElement;
  const selection = window.getSelection();
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, i) => selection.getRangeAt(i).cloneRange()) : [];
  const field = document.createElement('textarea');
  field.value = text;
  field.readOnly = true;
  field.style.position = 'fixed';
  field.style.opacity = '0';
  document.body.append(field);
  try {
    field.focus();
    field.select();
    if (typeof document.execCommand !== 'function' || !document.execCommand('copy')) {
      throw new Error('暂时无法复制，请选中文字复制');
    }
  } finally {
    field.remove();
    if (focused instanceof HTMLElement) focused.focus({ preventScroll: true });
    if (selection) {
      selection.removeAllRanges();
      ranges.forEach(range => selection.addRange(range));
    }
  }
}
