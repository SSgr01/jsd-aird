# Arctic Blue × Glacier Cyan UI

This is the current design specification for the entire JSD AIRD system,
including the enterprise AI workbench. It replaces the previous SETO warm-white,
graphite, lilac and peach styling. Reference: aura-ai-workspace-arctic-glacier.html
with the lighter primary-button revision.

## Color roles

| Role | Value | Application token |
| --- | --- | --- |
| Canvas | #F8FBFE | --app-background |
| Surface | #FFFFFF | --app-surface |
| Subtle surface | #F6FAFD | --app-surface-subtle |
| Focus / checked-control accent | #315F91 | --app-primary |
| Link / selected-label ink | #000000 | --app-text |
| Selected surface | #EDF5FC | --app-primary-soft |
| Primary button fill | #D1EAF9 | --app-action |
| Primary button hover | #C7E7F4 | --app-action-hover |
| Primary button active | #B5E1EB | --app-action-active |
| Primary button label | #000000 | --app-on-action |
| Main text | #000000 | --app-text |
| Secondary / muted / placeholder text | #000000 | --app-text-secondary / --app-text-muted |
| Border | #E8EEF4 | --app-border |
| Strong border | #DCE7EF | --app-border-strong |
| Success | #347552 / #F0F8F3 | --app-success / --app-success-soft |
| Warning | #92671F / #FCF7EC | --app-warning / --app-warning-soft |
| Danger | #B84A55 / #FDF1F2 | --app-danger / --app-danger-soft |

Never use the pale action fill as link text, a selected checkbox mark, or a focus
outline. Primary actions use black labels, not white labels. Primary buttons
have no dark shadow. Danger actions retain their red treatment. Disabled controls
remain distinct from enabled actions and must retain their disabled behavior.

Use CSS semantic variables for page styles. Ant Design's global accent stays
#315F91 for checked controls and focus; ordinary text, links and primary-button
labels use #000000. Only its Button component overrides filled-primary colors. The enterprise
plugin maps the same roles to native theme aliases. Its info-fill alias also
colors text/icons, so composer backgrounds use separate --jsd-action variables.
Strong state marks and switches retain inverse foregrounds.
The light AI theme uses the same black text. Dark-theme body text keeps its light
ink; black foregrounds are confined to pale action fills. Preserve semantic
success, warning and error colors, disabled states, inverse tooltip labels, and
colors stored inside documents, cells, diagrams and business data. Text hierarchy
comes from size, weight, spacing and position rather than pale text colors.

## Type, spacing and surfaces

- Keep the existing locally served Noto Sans SC Variable and system fallback stack.
- Body and controls: 14px; captions: 12px; page title: 24px (22px on phones).
- Normal text, secondary text and button labels must reach 4.5:1 contrast.
- Controls: 8–10px radius; cards: 12–16px; main panels: 20px.
- Keep the existing 4px spacing scale, business information architecture and operation order.
- White panels, faint cool borders and very small shadows separate content.
- Tables use neutral headers, aligned columns, faint row rules, pale hover and
  selected fills. Preserve numerical alignment, density and scroll ownership.
- Navigation uses white surfaces, quiet icons and pale blue selection with dark
  black text. Keep the existing 256px expanded / 64px collapsed ProLayout sidebar
  and 64px toolbar geometry.
- Menus, dialogs and drawers use opaque white surfaces and cool floating shadows.
- Blue/cyan background washes are limited to login and empty AI welcome areas.
  Working tables, cards, buttons and active conversations use flat fills.
- Keep current responsive behavior and honor prefers-reduced-motion.

## Desktop composition, second refinement

Structural changes below apply at widths of 1024px and above. Mobile layout is
explicitly deferred; retain its existing navigation, filters and list behavior.

- Frame ordinary business pages with a 20px canvas inset and a 22px shell radius.
  Document/spreadsheet editors, knowledge workspaces and the standalone AI route
  retain their own scroll and sizing contracts.
- Use white as the dominant working surface. Avoid nesting blue filter panels
  inside blue page panels. Reserve blue fill for selections and primary actions.
- Give the project records one 14px boundary, a compact section caption, a quiet
  header and 66px rows. Group name, project code and company in one identity cell;
  keep dates and team counts aligned. Derive totals from the existing query.
  Do not invent summary statistics or new business actions.
- Keep the full existing sidebar menu and group hierarchy. Add the shared brand
  mark, route context and the existing user menu in its desktop footer.
- Login uses the same brand mark, a 444px white card, 46px controls and localized
  blue/cyan background glows. The brand is not an extra illustration.
- AI welcome composition uses native hero/composer slots to group the vertically
  stacked brand, existing three task prompts and input capped at 700px in one
  centered cluster. Use a 24px gap from the prompt cards to the input, without
  automatic margins splitting the cluster. Active conversations retain their
  native bottom input position and prompt insertion behavior.
- Floating overlays use --app-shadow-overlay. Content cards use
  --app-shadow-card; the outer shell uses --app-shadow-shell. Primary buttons
  remain flat, pale blue, with deep blue labels and icons in every theme adapter.

Desktop shell CSS belongs in src/layouts/desktop-shell.css. Page-specific
composition belongs in its page stylesheet. Shared component structure and states
belong beside the component; src/styles/ui-refinement.css keeps system-wide
finishes. Avoid adding another global override sheet.

## Desktop category, filter, upload and empty-state components

- At widths of 1024px and above, CategoryCardGrid defaults to compact name/count
  options, wrapping as needed. Keep descriptions available on hover/focus and keep
  rename/delete actions independent of selection. Do not use repeated icon tiles.
- Use ListFilterBar for controlled filter descriptors. DesktopListFilters adapts
  existing controlled input groups while retaining their original layout below
  1024px. Controls are 36px tall; selects keep at least 144px and date ranges 248px.
  Search and frequent fields stay visible; low-frequency fields use More filters.
  Collapsing never clears values. Show an applied-filter summary and keep reset
  together with the filter toggle when the row wraps.
- CatalogListPanel uses a 14px list heading and a neutral count. Category names
  stay in the classification controls instead of being repeated as the list title.
- UploadWorkspace uses a 24px page title, one desktop breadcrumb in the app shell,
  and a 152px drop zone. Empty history panels take their natural height. Visibility
  and required classification remain visible. UploadMetadataFields can group
  optional metadata in a native disclosure without unmounting or clearing it;
  required fields are never hidden. Keep the previous form order on smaller screens.
- Use ListEmptyState after a successful empty read. Distinguish a new collection
  from no matching results; reuse an existing permission-gated action or offer
  Clear filters. Keep loading and error/retry feedback separate. An empty table
  should not reserve a fixed tall viewport. Scientific content and saved files
  are unaffected by these presentation rules.

## Editors, charts and AI workbench

Theme document/spreadsheet chrome only. Do not rewrite stored content, cell styles,
document formatting, scientific chart colors or source highlights to match chrome.
Keep semantic success/warning/error colors and distinct data series intact.

The standalone AI workbench retains its native light/dark and font preferences.
Dark surfaces are neutral, with pale blue/cyan accents and readable text. Use the
enterprise plugin's public DOM attributes and ARIA semantics for targeted styling;
do not depend on generated CSS-module class names or modify native interaction logic.

## Sources and verification

- CSS roles: src/styles/variables.css
- Ant Design: src/app/config/theme.ts
- Editor chrome: src/features/template-workspace/editor-theme.ts
- Native AI aliases and styling: jsd-aird-agent/enterprise-plugin/src/theme.mjs,
  theme.css and client.js
- Startup CSS remains self-contained because it is inlined before app CSS loads.
- Keep scripts/browser-css-compat.ts solid-color fallbacks synchronized for Chromium 106.
- Verify primary button interaction states, focus, disabled/loading controls,
  navigation, tables, forms, portals and AI send/continue controls.
- Capture representative pages at 1440, 768 and 390px; identify fixture data and
  unavailable backend workflows in the validation report.
