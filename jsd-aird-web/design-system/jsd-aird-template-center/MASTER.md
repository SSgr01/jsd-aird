# Monochrome system UI

The current system uses black, white and neutral gray. This replaces the Arctic
Blue × Glacier Cyan theme across business pages, login, shared controls, editor
chrome and the enterprise AI workbench.

## Color roles

| Role | Value | Application token |
| --- | --- | --- |
| Canvas | #FAFAFA | --app-background |
| Surface | #FFFFFF | --app-surface |
| Subtle surface | #F5F5F5 | --app-surface-subtle |
| Selected surface | #F0F0F0 | --app-primary-soft |
| Focus / checked control | #262626 | --app-primary |
| Primary button | #171717 | --app-action |
| Primary hover / active | #303030 / #0A0A0A | --app-action-hover / --app-action-active |
| Primary label | #FFFFFF | --app-on-action |
| Main text | #171717 | --app-text |
| Secondary text | #525252 | --app-text-secondary |
| Captions / placeholders | #666666 | --app-text-muted |
| Border / strong border | #E5E5E5 / #D4D4D4 | --app-border / --app-border-strong |
| Sidebar / hover | #F7F7F7 / #EDEDED | sidebar scope |
| Success | #347552 / #F0F8F3 | --app-success / --app-success-soft |
| Warning | #92671F / #FCF7EC | --app-warning / --app-warning-soft |
| Danger | #B84A55 / #FDF1F2 | --app-danger / --app-danger-soft |

Use semantic CSS variables. Ant Design and native AI aliases use matching values.
Primary actions have white labels on black fills. Outlined, text and ghost actions
retain dark ink. Checked controls and focus stay visible. Danger actions retain
red semantics. Disabled controls keep their real behavior and remain distinct.
Avatar and category colors are neutral; business states and saved data retain color.

No blue/cyan decoration, gradient grounds, glow, glass or colored shadows. Separate
surfaces with white, one gray tone step or a quiet border. Keep primary buttons flat.

## Typography and spacing

- Keep the existing locally served Noto Sans SC Variable and system fallback stack.
- Body/controls: 14px; captions: 12px; page titles: 24px (22px on phones).
- Ordinary text and button labels must meet 4.5:1 contrast. Hierarchy uses weight,
  size, spacing and readable gray, rather than faint text.
- Keep current control/card radii, 4px spacing scale, density and operation order.
- Preserve responsive behavior and prefers-reduced-motion. Mobile redesign is deferred.

## Shell and shared components

- Ordinary business pages fill the window: no outer margin, rounded frame or shell
  shadow. Sidebar and toolbar align with window edges. Keep internal content gutters.
- Sidebar stays 256px expanded / 64px collapsed; toolbar stays 64px high. Keep all
  permission-filtered menus and routes. Main rows are 40px; children 36px. Use quiet
  outline icons, white selected rows and gray hover feedback. Collapsed icons remain
  fully visible and centered. Keep brand/workspace copy and footer account menu.
- Tables retain aligned columns, faint rules, gray hover/selection, pagination,
  numeric alignment and scroll ownership. No invented statistics or business actions.
- Search, category filters, upload and empty states reuse existing shared components.
- CategoryCardGrid keeps compact desktop name/count options and separate edit actions.
- ListFilterBar/DesktopListFilters keep controlled values and their existing More
  filters disclosure. Collapsing does not clear values; required fields remain visible.
- CatalogListPanel retains compact headings and neutral counts.
- UploadWorkspace keeps existing metadata order, validation and upload behavior.
- ListEmptyState distinguishes empty collections from no matches. Preserve authorized
  actions, Clear filters, error/retry and loading feedback.
- Login retains its brand, card and layout on a plain neutral ground.
- Dialogs, drawers and menus use opaque surfaces and neutral floating shadows.

## Editors and AI

Theme document/spreadsheet chrome only. Do not rewrite saved content, cell styles,
document formatting, scientific chart series, source highlights or business data.

The standalone AI workbench retains native light/dark and font preferences. Its
default font is the same locally served Noto Sans SC Variable as business pages;
the plugin build packages that exact Fontsource dependency under lib/fonts/.
Both desktop shells fill the window without an outer frame, radius or shadow. Light
mode uses dark text, gray bubbles and black actions with white icons; dark mode uses
neutral dark surfaces, light text and inverse actions with dark icons. Suggestions,
expert identities, settings and navigation use the same neutral roles. Preserve
success/warning/error semantics, prompt insertion and active conversation behavior.
Use public DOM/ARIA contracts, not generated CSS-module names.

## Ownership and verification

- CSS roles: src/styles/variables.css; Ant Design: src/app/config/theme.ts
- Sidebar: src/layouts/sidebar.css; shell geometry: src/layouts/desktop-shell.css
- Shared finishes: src/styles/ui-refinement.css; page styles remain with each page
- Editor chrome: src/features/template-workspace/editor-theme.ts
- AI: enterprise-plugin/src/theme.mjs and theme.css; rebuild lib/client.js
- Chromium 106 fallbacks: scripts/browser-css-compat.ts
- Keep startup skeleton and browser theme-color aligned with the canvas.

Use scoped type/lint, existing theme/contrast/compatibility checks and AI plugin
build. Review actual browser screenshots and control states. Identify sample data
and untested backend operations. Full tests and deployment are outside this change.
