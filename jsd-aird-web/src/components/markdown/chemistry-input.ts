/** Shared by both Markdown renderers and the isolated RDKit viewer (see prepare-chemistry.mjs). */
export interface ChemistryEntry { label: string; source: string }

// SMILES lexical tokens, not molecule-name or subject keywords. RDKit performs chemical validation.
function smilesTokens(value: string): boolean {
  return value.length > 0 && /^(?:Br|Cl|[BCNOPSFIbcnops*]|\[[^\[\]\s]+\]|[0-9%()=#+\-/\\.:@~$>])+$/u.test(value)
}

/**
 * Parse labelled SMILES lines; chemical validation belongs to RDKit.
 * @param source - Newline-separated display labels and structure strings.
 * @param strict - Require SMILES lexical tokens when detecting a labelled list.
 * @returns Every parsed entry, or null when any line is not a labelled structure.
 */
export function chemistryList(source: string, strict = true): ChemistryEntry[] | null {
  const lines = source.split(/\r?\n/u).filter(line => line.trim())
  if (!lines.length) return null
  const items: ChemistryEntry[] = []
  for (const line of lines) {
    const match = /^\s*(.+?)\s*[:：]\s*(\S+)\s*$/u.exec(line)
    if (!match) return null
    const label = match[1]?.trim()
    const raw = match[2]
    if (!label || raw === undefined) return null
    const value = /^(?:".*"|'.*')$/u.test(raw) ? raw.slice(1, -1) : raw
    if (/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/u.test(label) || (strict && !smilesTokens(value))) return null
    items.push({ label, source: value })
  }
  return items
}
