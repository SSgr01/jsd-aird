// The editor sees this key before it reaches the workspace's window listener.
// Record ownership even if editing ends during the same event.
export const editorEscapeEvents = new WeakSet<KeyboardEvent>();
