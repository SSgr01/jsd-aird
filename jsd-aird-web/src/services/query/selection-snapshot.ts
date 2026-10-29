/** Keep the values the user selected even when background queries replace the rows. */
export function selectionSnapshot<T extends { id: string }>(previous: T[], ids: string[], visible: T[]): T[] {
  const originals = new Map(previous.map(row => [row.id, row]));
  const current = new Map(visible.map(row => [row.id, row]));
  return ids.map(id => originals.get(id) ?? current.get(id)).filter((row): row is T => row !== undefined);
}
