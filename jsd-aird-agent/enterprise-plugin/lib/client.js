window.__ModuleLoader__.load({ id: '@jsd-aird/enterprise-plugin', factory: require => {
  const React = require('react'), h = React.createElement;
  const { useEffect, useState, useRef } = React;
  const enterpriseTheme = {"tokens":{"--dsw-alias-bg-base":{"light":"#fafafa","dark":"#171717"},"--dsw-alias-bg-layer-1":{"light":"#ffffff","dark":"#202020"},"--dsw-alias-bg-layer-2":{"light":"#f5f5f5","dark":"#262626"},"--dsw-alias-bg-layer-3":{"light":"#ededed","dark":"#333333"},"--dsw-alias-bg-overlay":{"light":"#ffffff","dark":"#262626"},"--dsw-alias-bg-document-preview":{"light":"#ededed","dark":"#141414"},"--dsw-alias-label-document-preview":{"light":"#000000","dark":"#d4d4d4"},"--dsw-alias-border-l1":{"light":"#e5e5e5","dark":"#333333"},"--dsw-alias-border-l2":{"light":"#d4d4d4","dark":"#404040"},"--dsw-alias-border-l2-darkmode-thin":{"light":"#d4d4d4","dark":"#404040"},"--dsw-alias-border-l3":{"light":"#d4d4d4","dark":"#404040"},"--dsw-alias-border-l4":{"light":"#a3a3a3","dark":"#737373"},"--dsw-alias-brand-primary":{"light":"#262626","dark":"#e5e5e5"},"--dsw-alias-brand-primary-new-colorprimary-new-color":{"light":"#262626","dark":"#e5e5e5"},"--dsw-alias-onboarding-accent":{"light":"#262626","dark":"#e5e5e5"},"--dsw-alias-brand-primary-invert":{"light":"#ffffff","dark":"#262626"},"--dsw-alias-button-primary-fill":{"light":"#171717","dark":"#f5f5f5"},"--dsw-alias-button-primary-hover":{"light":"#303030","dark":"#dedede"},"--dsw-alias-button-info-fill":{"light":"#262626","dark":"#e5e5e5"},"--dsw-alias-button-info-hover":{"light":"#404040","dark":"#ffffff"},"--dsw-alias-button-ghost-active-border":{"light":"#737373","dark":"#a3a3a3"},"--dsw-alias-interactive-bg-hover":{"light":"rgb(38 38 38 / 6%)","dark":"rgb(255 255 255 / 8%)"},"--dsw-alias-interactive-bg-hover-solid":{"light":"#f5f5f5","dark":"#262626"},"--dsw-alias-interactive-bg-hover-accent":{"light":"#f0f0f0","dark":"#333333"},"--dsw-alias-interactive-bg-active":{"light":"#e5e5e5","dark":"#333333"},"--dsw-alias-label-primary":{"light":"#171717","dark":"#f5f5f5"},"--dsw-alias-label-primary-bluish":{"light":"#171717","dark":"#f5f5f5"},"--dsw-alias-label-primary-dimmed":{"light":"#525252","dark":"#d4d4d4"},"--dsw-alias-label-dimmed":{"light":"#666666","dark":"#949494"},"--dsw-alias-label-primary-foreground":{"light":"#ffffff","dark":"#171717"},"--dsw-alias-label-primary-inverted":{"light":"#ffffff","dark":"#262626"},"--dsw-alias-label-secondary":{"light":"#525252","dark":"#d4d4d4"},"--dsw-alias-label-tertiary":{"light":"#666666","dark":"#bdbdbd"},"--dsw-alias-label-caption":{"light":"#666666","dark":"#bdbdbd"},"--dsw-alias-label-deep-diving":{"light":"#525252","dark":"#e5e5e5"},"--dsw-alias-label-deep-diving-shimmer":{"light":"#666666","dark":"#d4d4d4"},"--dsw-alias-link":{"light":"#171717","dark":"#f5f5f5"},"--dsw-alias-menu-icon":{"light":"#525252","dark":"#d4d4d4"},"--dsw-alias-markdown-citation":{"light":"#f0f0f0","dark":"#333333"},"--dsw-alias-markdown-code-block":{"light":"#f5f5f5","dark":"#202020"},"--dsw-alias-markdown-code-block-banner":{"light":"#ededed","dark":"#262626"},"--dsw-alias-markdown-inline-code":{"light":"#f0f0f0","dark":"#333333"},"--dsw-alias-markdown-tag":{"light":"#f0f0f0","dark":"#333333"},"--dsw-alias-scrollbar-bg-l1":{"light":"#c4c4c4","dark":"#525252"},"--dsw-alias-scrollbar-bg-l2":{"light":"#c4c4c4","dark":"#525252"},"--dsw-alias-scrollbar-hover-l1":{"light":"#a3a3a3","dark":"#a3a3a3"},"--dsw-alias-scrollbar-hover-l2":{"light":"#a3a3a3","dark":"#a3a3a3"},"--dsw-alias-state-business-primary":{"light":"#262626","dark":"#e5e5e5"},"--dsw-alias-state-business-tertiary":{"light":"#f0f0f0","dark":"#333333"},"--dsw-alias-state-done-primary":{"light":"#262626","dark":"#e5e5e5"},"--dsw-alias-state-error-primary":{"light":"#b84a55","dark":"#ee969f"},"--dsw-alias-state-error-secondary":{"light":"#d97a84","dark":"#c96d78"},"--dsw-alias-state-success-primary":{"light":"#347552","dark":"#85cda5"},"--dsw-alias-state-success-secondary":{"light":"#559b74","dark":"#65ae87"},"--dsw-alias-state-success-tertiary":{"light":"#eaf4ed","dark":"#233c30"},"--dsw-alias-state-warn-primary":{"light":"#92671f","dark":"#e0b96d"},"--dsw-alias-state-warn-secondary":{"light":"#b58a43","dark":"#bf9955"},"--dsw-alias-state-warn-tertiary":{"light":"#faf2e5","dark":"#403522"},"--dsw-alias-state-warn-label":{"light":"#92671f","dark":"#e0b96d"},"--dsw-specific-bubble":{"light":"#f0f0f0","dark":"#262626"},"--dsw-specific-bubble-highlight":{"light":"#e5e5e5","dark":"#404040"},"--dsw-specific-input-major":{"light":"#ffffff","dark":"#202020"},"--dsw-specific-selector":{"light":"#f5f5f5","dark":"#262626"},"--dsw-specific-sidebar-fill":{"light":"#f7f7f7","dark":"#1c1c1c"},"--dsw-specific-sidebar-nav-item-active":{"light":"#ffffff","dark":"#333333"},"--dsw-specific-sidebar-nav-item-active-accent":{"light":"#ffffff","dark":"#333333"},"--dsw-specific-tip":{"light":"#f5f5f5","dark":"#262626"},"--dsw-menu-surface-fill":{"light":"#ffffff","dark":"#262626"},"--dsw-alias-menu-group-header-fill":{"light":"#f5f5f5","dark":"#262626"},"--dsw-alias-illustration-background":{"light":"#f5f5f5","dark":"#262626"},"--dsw-alias-illustration-backing":{"light":"#d4d4d4","dark":"#525252"},"--dsw-alias-illustration-surface":{"light":"#ededed","dark":"#333333"},"--dsw-alias-illustration-detail":{"light":"#bdbdbd","dark":"#737373"},"--dsw-alias-illustration-shadow":{"light":"#e5e5e5","dark":"#202020"},"--dsw-alias-illustration-inset":{"light":"#fafafa","dark":"#404040"}},"css":"/* The native settings shell owns the canvas. One full-width column uses a\r\n   16px inset for controls and 24px between sections; memory text gets the space. */\r\n.jsd-memory-settings {\r\n  container: jsd-memory / inline-size;\r\n  width: 100%; max-width: 760px;\r\n  color: var(--dsw-alias-label-primary);\r\n  font: 14px/1.6 var(--dsw-font-family);\r\n  padding-bottom: 8px;\r\n}\r\n.jsd-memory-header { margin-bottom: 20px; }\r\n.jsd-memory-header > div { display: flex; align-items: center; justify-content: space-between; gap: 16px; }\r\n.jsd-memory-settings h2 { margin: 0; font-size: 24px; line-height: 32px; font-weight: 600; letter-spacing: -.02em; }\r\n.jsd-memory-header > p { margin: 8px 0 0; color: var(--dsw-alias-label-secondary); font-size: 13px; line-height: 22px; text-wrap: pretty; }\r\n.jsd-memory-privacy { color: var(--dsw-alias-label-secondary); font-size: 12px; white-space: nowrap; }\r\n.jsd-memory-controls { padding-inline: 16px; border: 1px solid var(--dsw-alias-border-l2); border-radius: 10px; background: var(--dsw-alias-bg-layer-1); }\r\n.jsd-memory-switch { display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 72px; padding-block: 14px; box-sizing: border-box; }\r\n.jsd-memory-controls .jsd-memory-switch + .jsd-memory-switch { border-top: 1px solid var(--dsw-alias-border-l1); }\r\n.jsd-memory-switch-copy { min-width: 0; }\r\n.jsd-memory-switch strong { display: block; font-weight: 500; line-height: 22px; }\r\n.jsd-memory-switch p { margin: 3px 0 0; color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 20px; text-wrap: pretty; }\r\n.jsd-memory-toggle::after { content: ''; position: absolute; inset: -12px -4px; }\r\n.jsd-memory-settings .jsd-memory-button, .jsd-session-memory .jsd-memory-button {\r\n  display: inline-flex; align-items: center; justify-content: center; flex: none; gap: 6px;\r\n  min-height: 34px; padding: 5px 12px; box-sizing: border-box;\r\n  border: 1px solid var(--dsw-alias-border-l2); border-radius: 6px;\r\n  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary);\r\n  font: 500 13px/22px var(--dsw-font-family); cursor: pointer;\r\n  transition: background-color 140ms ease, color 140ms ease, border-color 140ms ease;\r\n}\r\n.jsd-memory-settings .jsd-memory-button:hover:not(:disabled), .jsd-session-memory .jsd-memory-button:hover:not(:disabled) { background: var(--dsw-alias-interactive-bg-hover); }\r\n.jsd-memory-settings .jsd-memory-button:active:not(:disabled), .jsd-session-memory .jsd-memory-button:active:not(:disabled) { transform: translateY(1px); }\r\n.jsd-memory-settings .jsd-memory-primary { background: var(--dsw-alias-button-primary-fill); border-color: transparent; color: var(--dsw-alias-label-primary-foreground); }\r\n.jsd-memory-settings .jsd-memory-primary:hover:not(:disabled) { background: var(--dsw-alias-button-primary-hover); }\r\n.jsd-memory-settings .jsd-memory-text-button, .jsd-session-memory .jsd-memory-text-button { background: transparent; border-color: transparent; color: var(--dsw-alias-label-secondary); padding-inline: 8px; }\r\n.jsd-memory-settings .jsd-memory-text-button:hover:not(:disabled) { color: var(--dsw-alias-label-primary); }\r\n.jsd-memory-settings .jsd-memory-danger:hover:not(:disabled) { color: var(--dsw-alias-state-error-primary); }\r\n.jsd-memory-settings .jsd-memory-button:disabled, .jsd-session-memory .jsd-memory-button:disabled { color: var(--dsw-alias-label-secondary); background: var(--dsw-alias-bg-layer-2); cursor: default; }\r\n.jsd-memory-settings .jsd-memory-text-button:disabled, .jsd-session-memory .jsd-memory-text-button:disabled { background: transparent; }\r\n.jsd-memory-settings :focus-visible, .jsd-session-memory :focus-visible { outline: 2px solid var(--jsd-focus); outline-offset: 3px; }\r\n.jsd-memory-notice { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 8px; color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n.jsd-memory-notice p { margin: 0; }\r\n.jsd-memory-collection { margin-top: 24px; }\r\n.jsd-memory-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }\r\n.jsd-memory-list-heading > div { display: flex; align-items: baseline; gap: 10px; }\r\n.jsd-memory-settings h3 { margin: 0; font-size: 15px; line-height: 24px; font-weight: 600; }\r\n.jsd-memory-count { color: var(--dsw-alias-label-secondary); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }\r\n.jsd-memory-list { list-style: none; padding: 0; margin: 0; }\r\n.jsd-memory-entry { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: 8px 12px; padding-block: 18px; border-bottom: 1px solid var(--dsw-alias-border-l1); }\r\n.jsd-memory-entry:first-child { padding-top: 12px; }\r\n.jsd-memory-entry[data-editing] { grid-template-columns: minmax(0, 1fr); }\r\n.jsd-memory-entry-body { min-width: 0; }\r\n.jsd-memory-entry-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 20px; }\r\n.jsd-memory-status { display: inline-flex; align-items: center; gap: 5px; }\r\n.jsd-memory-status-dot { display: inline-block; flex: none; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }\r\n.jsd-memory-status[data-state='ACTIVE'] .jsd-memory-status-dot, .jsd-memory-status[data-state='SUCCEEDED'] .jsd-memory-status-dot,\r\n.jsd-memory-receipt[data-state='SUCCEEDED'] .jsd-memory-status-dot { color: var(--dsw-alias-state-success-primary); }\r\n.jsd-memory-status[data-state='FAILED'], .jsd-memory-receipt[data-state='FAILED'] .jsd-memory-status-dot { color: var(--dsw-alias-state-error-primary); }\r\n.jsd-memory-status[data-state='PENDING'] .jsd-memory-status-dot, .jsd-memory-status[data-state='UNKNOWN'] .jsd-memory-status-dot { color: var(--dsw-alias-state-warn-primary); }\r\n.jsd-memory-entry-text { margin: 6px 0 8px; white-space: pre-wrap; overflow-wrap: anywhere; text-wrap: pretty; line-height: 24px; }\r\n.jsd-memory-entry-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 14px; color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 20px; }\r\n.jsd-memory-entry-meta time { font-variant-numeric: tabular-nums; }\r\n.jsd-memory-settings .jsd-memory-source { min-height: 24px; padding: 0; border-radius: 3px; font-size: 12px; font-weight: 400; }\r\n.jsd-memory-settings .jsd-memory-source:hover:not(:disabled) { background: transparent; text-decoration: underline; text-underline-offset: 3px; }\r\n.jsd-memory-entry-actions { display: flex; align-items: center; margin: -5px -8px 0 0; }\r\n.jsd-memory-feedback { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; margin-bottom: 12px; padding: 8px 12px; border-radius: 6px; background: var(--dsw-alias-bg-layer-2); font-size: 12px; line-height: 22px; }\r\n.jsd-memory-feedback > span { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }\r\n.jsd-memory-feedback small { color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n.jsd-memory-feedback .jsd-memory-button { min-height: 26px; padding-block: 1px; }\r\n.jsd-memory-error { color: var(--dsw-alias-state-error-primary); }\r\n.jsd-memory-editor { margin-block: 12px 16px; padding: 16px; border: 1px solid var(--dsw-alias-border-l2); border-radius: 8px; background: var(--dsw-alias-bg-layer-1); }\r\n.jsd-memory-editor label { display: block; font-size: 13px; font-weight: 500; }\r\n.jsd-memory-editor textarea { display: block; width: 100%; min-height: 100px; box-sizing: border-box; margin-top: 10px; padding: 10px 12px; border: 1px solid var(--dsw-alias-border-l2); border-radius: 6px; background: var(--dsw-alias-bg-base); color: var(--dsw-alias-label-primary); font: 400 14px/24px var(--dsw-font-family); resize: vertical; }\r\n.jsd-memory-editor textarea::placeholder { color: var(--dsw-alias-label-secondary); }\r\n.jsd-memory-editor-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-top: 12px; }\r\n.jsd-memory-editor-footer > span { color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n.jsd-memory-editor-footer > div { display: flex; justify-content: flex-end; gap: 4px; margin-left: auto; }\r\n.jsd-memory-empty { padding: 24px; border: 1px solid var(--dsw-alias-border-l1); border-radius: 8px; background: var(--dsw-alias-bg-layer-1); }\r\n.jsd-memory-empty h4 { margin: 0; font-size: 14px; line-height: 24px; font-weight: 500; }\r\n.jsd-memory-empty p { margin: 6px 0 0; color: var(--dsw-alias-label-secondary); font-size: 13px; line-height: 22px; text-wrap: pretty; }\r\n.jsd-memory-empty .jsd-memory-example { margin-top: 16px; padding: 10px 12px; border-radius: 5px; background: var(--dsw-alias-bg-layer-2); font-size: 12px; }\r\n.jsd-memory-loading { padding-inline: 16px; border: 1px solid var(--dsw-alias-border-l1); border-radius: 10px; }\r\n.jsd-memory-loading > div { display: flex; flex-direction: column; gap: 10px; padding-block: 20px; }\r\n.jsd-memory-loading > div + div { border-top: 1px solid var(--dsw-alias-border-l1); }\r\n.jsd-memory-loading span { width: 96px; height: 12px; border-radius: 3px; background: var(--dsw-alias-bg-layer-3); }\r\n.jsd-memory-loading span + span { width: 70%; height: 10px; }\r\n.jsd-memory-settings .jsd-memory-more { display: flex; margin: 16px auto 0; }\r\n.jsd-session-memory { min-width: 0; color: var(--dsw-alias-label-primary); font: 14px/22px var(--dsw-font-family); }\r\n\r\n/* WorkBuddy reference: sidebar page, quiet expert cards, role detail and composer menu. */\r\n.jsd-expert-page { height: 100%; min-width: 0; overflow-y: auto; container-type: inline-size; background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); font: 14px/1.6 var(--dsw-font-family); }\r\n.jsd-expert-page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 24px 32px 20px; padding-left: max(32px, var(--dsh-frame-leading-clearance, 0px)); }\r\n.jsd-expert-page h1 { display: flex; align-items: center; gap: 8px; font-size: 20px; font-weight: 600; margin: 0; }\r\n.jsd-expert-page input, .jsd-expert-quick input { border: 1px solid var(--dsw-alias-border-l2); border-radius: 8px; padding: 8px 12px; color: inherit; background: var(--dsw-alias-bg-layer-1); font: inherit; min-width: 0; }\r\n.jsd-expert-page-header input { width: 240px; }\r\n.jsd-experts { max-width: 1240px; margin: auto; padding: 16px 32px 32px; }\r\n.jsd-expert-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }\r\n.jsd-expert-heading h2 { font-size: 16px; margin: 0 0 4px; }\r\n.jsd-expert-heading p { color: var(--dsw-alias-label-secondary); font-size: 13px; margin: 0; }\r\n.jsd-expert-button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 32px; padding: 5px 10px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--dsw-alias-label-primary); font: inherit; font-size: 13px; cursor: pointer; text-align: left; }\r\n.jsd-expert-button:hover:not(:disabled) { background: var(--dsw-alias-interactive-bg-hover); }\r\n.jsd-expert-button:disabled { opacity: .55; cursor: default; }\r\n.jsd-expert-button:focus-visible, .jsd-expert-page input:focus-visible, .jsd-expert-quick input:focus-visible, .jsd-expert-role summary:focus-visible { outline: 2px solid var(--jsd-focus); outline-offset: 2px; }\r\n.jsd-expert-primary { background: var(--dsw-alias-button-primary-fill); color: var(--dsw-alias-label-primary-foreground); }\r\n.jsd-expert-primary:hover:not(:disabled) { background: var(--dsw-alias-button-primary-hover); }\r\n.jsd-expert-views { display: flex; gap: 2px; padding: 2px; border: 1px solid var(--dsw-alias-border-l1); border-radius: 8px; flex-shrink: 0; }\r\n.jsd-expert-views .jsd-expert-button[aria-pressed=\"true\"], .jsd-expert-categories .jsd-expert-button[aria-pressed=\"true\"] { background: var(--dsw-alias-interactive-bg-active); }\r\n.jsd-expert-categories { display: flex; gap: 4px; align-items: center; margin: 24px 0 18px; flex-wrap: wrap; }\r\n.jsd-expert-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 16px; }\r\n.jsd-expert-card { min-width: 0; padding: 14px; border: 1px solid var(--dsw-alias-border-l2); border-radius: 12px; background: var(--dsw-alias-bg-layer-1); }\r\n.jsd-expert-card-top, .jsd-expert-card-bottom { display: flex; gap: 8px; align-items: center; justify-content: space-between; }\r\n.jsd-expert-card-title { padding: 0; gap: 10px; min-width: 0; justify-content: flex-start; }\r\n.jsd-expert-card-title > span:last-child { min-width: 0; }\r\n.jsd-expert-card-title strong { font-size: 14px; font-weight: 600; display: block; }\r\n.jsd-expert-card-title small { display: block; font-size: 12px; color: var(--dsw-alias-label-secondary); }\r\n.jsd-expert-card .jsd-expert-pin { padding: 0; min-width: 28px; font-size: 19px; color: var(--dsw-alias-label-secondary); }\r\n.jsd-expert-card > p { color: var(--dsw-alias-label-secondary); font-size: 13px; margin: 12px 0 10px; min-height: 21px; }\r\n.jsd-expert-card-bottom { justify-content: flex-end; border-top: 1px solid var(--dsw-alias-border-l1); padding-top: 8px; margin-top: 12px; }\r\n.jsd-expert-card-bottom > .jsd-expert-button { border-color: var(--dsw-alias-border-l2); padding-inline: 14px; }\r\n.jsd-expert-tags { display: flex; flex-wrap: wrap; gap: 5px; }\r\n.jsd-expert-tags > span { color: var(--dsw-alias-label-secondary); background: var(--dsw-alias-interactive-bg-hover); padding: 2px 6px; border-radius: 4px; font-size: 13px; }\r\n.jsd-expert-avatar { display: inline-flex; align-items: center; justify-content: center; width: 44px; height: 44px; flex: 0 0 auto; border-radius: 13px; background: var(--dsw-alias-bg-layer-2); color: var(--dsw-alias-label-secondary); }\r\n.jsd-expert-avatar svg { display: block; width: 25px; height: 25px; }\r\n.jsd-expert-avatar-small { width: 22px; height: 22px; border-radius: 7px; }\r\n.jsd-expert-avatar-small svg { width: 15px; height: 15px; stroke-width: 1.8; }\r\n.jsd-expert-dialog { width: min(620px,100%); max-height: 100%; background: var(--dsw-alias-bg-layer-1); }\r\n.jsd-expert-content { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }\r\n.jsd-expert-detail { color: var(--dsw-alias-label-primary); font: 14px/1.6 var(--dsw-font-family); }\r\n.jsd-expert-identity { display: flex; gap: 12px; align-items: center; }\r\n.jsd-expert-identity > div { flex: 1; min-width: 0; }\r\n.jsd-expert-identity h3 { font-size: 20px; margin: 0; line-height: 30px; }\r\n.jsd-expert-identity p { font-size: 12px; color: var(--dsw-alias-label-secondary); margin: 2px 0 0; }\r\n.jsd-expert-detail .jsd-expert-avatar { width: 56px; height: 56px; font-size: 16px; }\r\n.jsd-expert-summary { margin: 20px 0 12px; }\r\n.jsd-expert-meta { display: flex; gap: 24px; border-block: 1px solid var(--dsw-alias-border-l1); padding: 14px 0; margin-top: 20px; color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n.jsd-expert-detail h4 { font-size: 14px; margin: 24px 0 6px; }\r\n.jsd-expert-note { color: var(--dsw-alias-label-secondary); font-size: 12px; margin: 8px 0 12px; }\r\n.jsd-expert-examples { display: grid; gap: 8px; }\r\n.jsd-expert-examples .jsd-expert-button { justify-content: space-between; gap: 16px; padding: 12px; background: var(--dsw-alias-interactive-bg-hover); border-radius: 10px; font-size: 13px; line-height: 1.6; }\r\n.jsd-expert-examples .jsd-expert-button > span:last-child { color: var(--dsw-alias-label-secondary); flex-shrink: 0; }\r\n.jsd-expert-role { color: var(--dsw-alias-label-secondary); font-size: 13px; margin-top: 24px; }\r\n.jsd-expert-role summary { cursor: pointer; }\r\n.jsd-expert-role p { margin-top: 8px; white-space: pre-wrap; }\r\n.jsd-expert-config dl { display: grid; grid-template-columns: 72px minmax(0,1fr); gap: 14px 16px; margin: 16px 0; font-size: 13px; }\r\n.jsd-expert-config dt { color: var(--dsw-alias-label-secondary); }\r\n.jsd-expert-config dd { margin: 0; overflow-wrap: anywhere; }\r\n.jsd-expert-config dd p { margin: 4px 0 0; color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n.jsd-expert-config ul { margin: 0; padding-left: 16px; }\r\n.jsd-expert-config li + li { margin-top: 6px; }\r\n.jsd-expert-empty { padding: 32px 16px; text-align: center; border: 1px solid var(--dsw-alias-border-l1); border-radius: 10px; }\r\n.jsd-expert-error { padding: 10px 12px; margin: 8px 0 16px; border: 1px solid var(--dsw-alias-border-l2); border-radius: 6px; color: var(--dsw-alias-state-error-primary); }\r\n.jsd-expert-selector { display: flex; align-items: center; min-width: 0; }\r\n.jsd-expert-selector > span { font-size: 12px; color: var(--dsw-alias-label-secondary); }\r\n.jsd-expert-selector .jsd-expert-button { white-space: nowrap; font-size: 12px; padding: 4px 6px; }\r\n.jsd-expert-quick-menu { width: 300px; max-width: calc(100vw - 32px); }\r\n.jsd-expert-quick { padding: 4px; font: 13px/1.6 var(--dsw-font-family); }\r\n.jsd-expert-quick input { width: 100%; margin-bottom: 8px; }\r\n.jsd-expert-quick p { padding-inline: 8px; }\r\n.jsd-expert-welcome { color: var(--dsw-alias-label-primary); font: 14px/1.6 var(--dsw-font-family); padding: 12px 0; }\r\n.jsd-expert-welcome .jsd-expert-identity { margin-bottom: 16px; }\r\n.jsd-expert-welcome p { color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n@container (max-width: 1050px) { .jsd-expert-grid { grid-template-columns: repeat(2,minmax(0,1fr)); } }\r\n@container (max-width: 640px) {\r\n  .jsd-expert-page-header { padding: 24px 20px 16px; padding-left: max(20px, var(--dsh-frame-leading-clearance, 0px)); flex-wrap: wrap; }\r\n  .jsd-expert-page-header input { width: 100%; }\r\n  .jsd-experts { padding: 12px 20px 24px; }\r\n  .jsd-expert-heading { align-items: flex-start; flex-direction: column; gap: 12px; }\r\n  .jsd-expert-grid { grid-template-columns: minmax(0,1fr); }\r\n  .jsd-expert-categories { flex-wrap: nowrap; overflow-x: auto; scrollbar-width: none; }\r\n  .jsd-expert-categories::-webkit-scrollbar { display: none; }\r\n  .jsd-expert-categories .jsd-expert-button { flex-shrink: 0; }\r\n}\r\n@media (max-width: 640px) {\r\n  .jsd-expert-button { min-height: 44px; }\r\n  .jsd-expert-identity { flex-wrap: wrap; }\r\n  .jsd-expert-detail .jsd-expert-primary { width: 100%; }\r\n}\r\n.jsd-session-memory > p { margin: 0; color: var(--dsw-alias-label-secondary); }\r\n.jsd-session-memory .jsd-memory-switch { min-height: 64px; padding-block: 10px; gap: 16px; }\r\n.jsd-session-memory .jsd-memory-switch + .jsd-memory-switch { border-top: 1px solid var(--dsw-alias-border-l1); }\r\n.jsd-session-memory .jsd-memory-feedback { margin: 8px 0 0; overflow-wrap: anywhere; }\r\n@container jsd-memory (max-width: 520px) {\r\n  .jsd-memory-header > div { gap: 12px; }\r\n  .jsd-memory-settings h2 { font-size: 22px; }\r\n  .jsd-memory-controls { padding-inline: 14px; }\r\n  .jsd-memory-switch { gap: 16px; }\r\n  .jsd-memory-switch-copy { max-width: calc(100% - 52px); }\r\n  .jsd-memory-entry { grid-template-columns: minmax(0, 1fr); gap: 4px; }\r\n  .jsd-memory-entry-actions { justify-self: end; margin-top: -6px; }\r\n  .jsd-memory-empty { padding: 18px; }\r\n  .jsd-memory-editor { padding: 14px; }\r\n  .jsd-memory-editor-footer > span { flex-basis: 100%; }\r\n}\r\n@media (max-width: 520px) {\r\n  .jsd-memory-settings .jsd-memory-button, .jsd-session-memory .jsd-memory-button { min-height: 44px; }\r\n  .jsd-memory-settings .jsd-memory-source { min-height: 44px; }\r\n  .jsd-memory-notice { align-items: flex-start; }\r\n}\r\n@media (prefers-reduced-motion: reduce) {\r\n  .jsd-memory-settings .jsd-memory-button, .jsd-session-memory .jsd-memory-button { transition: none; }\r\n  .jsd-memory-settings .jsd-memory-button:active:not(:disabled), .jsd-session-memory .jsd-memory-button:active:not(:disabled) { transform: none; }\r\n}\r\n/* Enterprise visual layer. Select native public data attributes and our own\r\n   slots only; upstream CSS-module names and interaction state stay untouched. */\r\n/* Keep one primary action while the native submission awaits acknowledgement.\r\n   The sending button stays in place and becomes Stop when admission completes. */\r\nbody[data-jsd-workbench] [data-composer-card] button:has(> svg > rect):has(~ button[data-sending]) {\r\n  display: none;\r\n}\r\n/* The server does not expose desktop Explorer or physical transcript moves. */\r\nbody[data-jsd-workbench] div:has(> [data-dsham-open-explorer=\"true\"]),\r\nbody[data-jsd-workbench] .dsham_moveSessionWrap {\r\n  display: none;\r\n}\r\nbody[data-jsd-workbench] {\r\n  --dsh-font-family-text-default: 'Noto Sans SC Variable', 'Microsoft YaHei UI', 'PingFang SC', 'Segoe UI', sans-serif;\r\n  --dsw-font-family-brand: var(--dsw-font-family);\r\n  --dsw-radius-panel: 20px;\r\n  --jsd-focus: #262626;\r\n  --jsd-action: #171717;\r\n  --jsd-action-hover: #303030;\r\n  --jsd-action-active: #0a0a0a;\r\n  --jsd-on-action: #ffffff;\r\n  --jsd-composer-shadow: rgb(0 0 0 / 4%);\r\n  --jsd-workspace-wash: none;\r\n}\r\n\r\nbody[data-jsd-workbench][data-ds-dark-theme] {\r\n  --jsd-focus: #e5e5e5;\r\n  --jsd-action: #f5f5f5;\r\n  --jsd-action-hover: #dedede;\r\n  --jsd-action-active: #d4d4d4;\r\n  --jsd-on-action: #171717;\r\n  --jsd-composer-shadow: rgb(0 0 0 / 18%);\r\n  --jsd-workspace-wash: none;\r\n}\r\n\r\nbody[data-jsd-workbench] [data-conversation-content] {\r\n  background-image: var(--jsd-workspace-wash);\r\n}\r\n\r\nbody[data-jsd-workbench] [data-conversation-content]:has([data-chat-flow-kind]) {\r\n  background-image: none;\r\n}\r\n\r\n/* Strong state marks retain inverse ink. The primary-button foreground must\r\n   not turn a checked switch thumb or a completed step into unreadable marks. */\r\nbody[data-jsd-workbench] :is([role='switch'], [role='checkbox']) {\r\n  --dsw-alias-label-primary-foreground: var(--dsw-alias-label-primary-inverted);\r\n}\r\nbody[data-jsd-workbench] span[data-state='done'][aria-hidden='true']:has(> svg) {\r\n  color: var(--dsw-alias-label-primary-inverted);\r\n}\r\n\r\n/* Native send/stop controls have fixed white glyphs upstream. Match only their\r\n   public composer/ARIA contract, never generated CSS-module class names. */\r\nbody[data-jsd-workbench] [data-composer-card] button:not([aria-expanded]):is([aria-busy], :has(> svg > rect[width='10'][height='10'])),\r\nbody[data-jsd-workbench] .jsd-continue-action {\r\n  color: var(--jsd-on-action);\r\n  background: var(--jsd-action);\r\n  box-shadow: none;\r\n}\r\nbody[data-jsd-workbench] [data-composer-card] button:not([aria-expanded]):is([aria-busy], :has(> svg > rect[width='10'][height='10'])):hover:not(:disabled),\r\nbody[data-jsd-workbench] .jsd-continue-action:hover:not(:disabled) {\r\n  background: var(--jsd-action-hover);\r\n}\r\nbody[data-jsd-workbench] [data-composer-card] button:not([aria-expanded]):is([aria-busy], :has(> svg > rect[width='10'][height='10'])):active:not(:disabled),\r\nbody[data-jsd-workbench] .jsd-continue-action:active:not(:disabled) {\r\n  background: var(--jsd-action-active);\r\n}\r\n\r\nbody[data-jsd-workbench] [data-composer-card] {\r\n  --dsw-elevation-stroke-color: var(--dsw-alias-border-l2);\r\n  box-shadow: 0 4px 24px var(--jsd-composer-shadow), 0 0 0 1px var(--dsw-alias-border-l2);\r\n  transition: box-shadow 160ms ease;\r\n}\r\n\r\nbody[data-jsd-workbench] [data-composer-card]:focus-within {\r\n  box-shadow: 0 4px 24px var(--jsd-composer-shadow), 0 0 0 1px var(--jsd-focus), 0 0 0 4px color-mix(in srgb, var(--jsd-focus) 12%, transparent);\r\n}\r\n\r\n.jsd-brand-mark {\r\n  display: inline-flex;\r\n  flex: none;\r\n  color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.jsd-brand-mark img { display: block; }\r\n.jsd-welcome-description { display: none; }\r\n.jsd-business-tool .jsd-tool-header {\r\n  flex: 1;\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  font-size: var(--dsh-content-font-size-secondary, 13px);\r\n  line-height: calc(24px + var(--dsh-content-font-delta, 0px));\r\n}\r\n.jsd-business-tool .jsd-tool-header-content { min-width: 0; overflow: hidden; }\r\n.jsd-tool-summary {\r\n  display: block;\r\n  flex: 1;\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  white-space: nowrap;\r\n  text-overflow: ellipsis;\r\n}\r\n.jsd-tool-content {\r\n  padding: 8px 24px;\r\n  font: var(--dsh-content-font-size, 14px)/1.75 var(--dsw-font-family);\r\n  overflow-wrap: anywhere;\r\n}\r\n.jsd-tool-content p { margin: 0 0 8px; }\r\n.jsd-brand-name { font-weight: 600; letter-spacing: -.02em; }\r\n.jsd-page-title {\r\n  margin: 0 20px 0 0;\r\n  padding-inline-start: max(0px, calc(var(--dsh-frame-leading-clearance, 0px) - 20px));\r\n  font: 600 18px/30px var(--dsw-font-family);\r\n  color: var(--dsw-alias-label-primary);\r\n  white-space: nowrap;\r\n}\r\n\r\n.jsd-starters {\r\n  display: grid;\r\n  grid-template-columns: repeat(2, minmax(0, 1fr));\r\n  gap: 12px;\r\n  margin-block: 12px;\r\n  margin-inline: var(--dsh-composer-side-clearance, 16px);\r\n}\r\n.jsd-starters button {\r\n  display: grid;\r\n  align-content: start;\r\n  justify-items: start;\r\n  gap: 8px;\r\n  min-width: 0;\r\n  padding: 20px 18px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 12px;\r\n  background: var(--dsw-alias-bg-layer-1);\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: pointer;\r\n  transition: border-color 160ms ease, box-shadow 160ms ease;\r\n}\r\n.jsd-starters button:hover {\r\n  border-color: var(--jsd-focus);\r\n  box-shadow: 0 3px 12px var(--jsd-composer-shadow);\r\n}\r\n.jsd-starters button:focus-visible {\r\n  outline: 2px solid var(--jsd-focus);\r\n  outline-offset: 3px;\r\n}\r\n.jsd-starters button:disabled { opacity: .5; cursor: default; }\r\n.jsd-starter-art { display: block; width: 36px; height: 36px; margin-bottom: 10px; }\r\n.jsd-starter-art svg { display: block; width: 100%; height: 100%; }\r\n.jsd-starters strong { font-size: 14px; line-height: 20px; font-weight: 500; }\r\n.jsd-starter-description { color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 20px; text-wrap: pretty; }\r\n\r\n.jsd-nav {\r\n  display: grid;\r\n  grid-template-columns: minmax(0, 1fr);\r\n  width: 100%;\r\n  padding: 10px 4px;\r\n  border-top: 1px solid var(--dsw-alias-border-l1);\r\n  box-sizing: border-box;\r\n}\r\n\r\n.jsd-nav a {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 8px;\r\n  min-height: 42px;\r\n  padding: 0 8px;\r\n  border-radius: var(--dsw-radius-sm);\r\n  color: var(--dsw-alias-label-secondary);\r\n  font-size: 14px;\r\n  line-height: 22px;\r\n  text-decoration: none;\r\n  transition: background-color 160ms ease, color 160ms ease;\r\n}\r\n\r\n.jsd-nav a:hover {\r\n  color: var(--dsw-alias-label-primary);\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.jsd-nav a:focus-visible,\r\n.jsd-resource a:focus-visible,\r\n.jsd-continue-action:focus-visible {\r\n  outline: 2px solid var(--jsd-focus);\r\n  outline-offset: 2px;\r\n}\r\n\r\n.jsd-nav[data-wide='false'] { grid-template-columns: 1fr; padding-inline: 0; }\r\n.jsd-nav[data-wide='false'] a { justify-content: center; padding: 0; }\r\n.jsd-nav svg { flex: none; width: 16px; height: 16px; }\r\n\r\n.jsd-resource {\r\n  padding: 24px;\r\n  font: var(--dsh-content-font-size, 14px)/1.8 var(--dsw-font-family);\r\n  overflow: auto;\r\n  height: 100%;\r\n  box-sizing: border-box;\r\n  color: var(--dsw-alias-label-primary);\r\n}\r\n.jsd-resource h2 { margin: 0 0 12px; font-size: 20px; font-weight: 600; }\r\n.jsd-resource pre { font: inherit; white-space: pre-wrap; overflow-wrap: anywhere; }\r\n.jsd-resource section { margin-block: 20px; }\r\n.jsd-resource a, .jsd-original-toolbar a { color: var(--dsw-alias-link); }\r\n.jsd-resource small { color: var(--dsw-alias-label-secondary); }\r\n.jsd-original { height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }\r\n.jsd-original-toolbar {\r\n  display: flex;\r\n  justify-content: space-between;\r\n  gap: 16px;\r\n  padding: 12px 16px;\r\n  border-bottom: 1px solid var(--dsw-alias-border-l1);\r\n  background: var(--dsw-alias-bg-layer-1);\r\n  font-size: 13px;\r\n}\r\n/* Keep the native primary action's layout seat; the empty paused state uses\r\n   the input extension to place Continue in that same seat. */\r\nbody[data-jsd-workbench] [data-composer-card]:has(.jsd-continue-action) button[aria-busy]:disabled:not([aria-expanded]):not(.jsd-continue-action) {\r\n  visibility: hidden;\r\n}\r\n.jsd-continue-action {\r\n  position: absolute;\r\n  right: 8px;\r\n  bottom: 8px;\r\n  z-index: 1;\r\n  display: grid;\r\n  place-items: center;\r\n  width: 34px;\r\n  height: 34px;\r\n  padding: 0;\r\n  border: 0;\r\n  border-radius: 999px;\r\n  corner-shape: round;\r\n  background: var(--jsd-action);\r\n  color: var(--jsd-on-action);\r\n  cursor: pointer;\r\n}\r\n.jsd-continue-action > svg:not(.jsd-continue-spinner) { transform: scale(1.5); }\r\n.jsd-continue-action:hover:not(:disabled) { background: var(--jsd-action-hover); }\r\n.jsd-continue-action:disabled { opacity: .4; cursor: default; }\r\n.jsd-continue-action[aria-busy='true']:disabled { opacity: 1; cursor: progress; }\r\n.jsd-continue-spinner { animation: jsd-continue-spin 800ms linear infinite; }\r\n@keyframes jsd-continue-spin { to { transform: rotate(360deg); } }\r\n\r\n/* Group the empty welcome and input in the native composer's slots.\r\n   Keep the input mounted and keep active conversations under native geometry.\r\n   Public slot/data attributes are used instead of generated CSS-module names. */\r\n@media (min-width: 1024px) {\r\n  body[data-jsd-workbench] { margin: 0; padding: 0; box-sizing: border-box; background: var(--dsw-alias-bg-base); }\r\n  body[data-jsd-workbench] #root {\r\n    box-sizing: border-box;\r\n    height: 100dvh;\r\n    min-height: 0;\r\n    overflow: hidden;\r\n    border: 0;\r\n    border-radius: 0;\r\n    box-shadow: none;\r\n  }\r\n  body[data-jsd-workbench] #root > div { height: 100%; min-height: 0; }\r\n  body[data-jsd-workbench] [data-conversation-content] { background-color: var(--dsw-alias-bg-layer-1); }\r\n  body[data-jsd-workbench] [data-content-phase='hero'] { --dsh-composer-card-max-width: 700px; }\r\n  body[data-jsd-workbench] [data-content-phase='hero'] [data-conversation-scroll] { justify-content: flex-start; }\r\n  body[data-jsd-workbench] [data-content-phase='hero'] [data-composer-seat] { flex: 1; }\r\n  body[data-jsd-workbench] [data-content-phase='hero'] [data-chain-overlay-fallback='conversation.composer'] > div {\r\n    flex: 1;\r\n    min-height: 580px;\r\n    justify-content: center;\r\n    padding: 20px 0 32px;\r\n  }\r\n  body[data-jsd-workbench] [data-content-phase='hero'] [data-chain-overlay-fallback='conversation.composer'] > div > div:has([data-slot='conversation.hero.brand.mark']) {\r\n    height: auto;\r\n    flex: none;\r\n    margin: 0;\r\n  }\r\n  body[data-jsd-workbench] [data-content-phase='hero'] [data-chain-overlay-fallback='conversation.composer'] > div:has(.jsd-starters) > div:has([data-slot='conversation.hero.brand.mark']) { margin-bottom: 16px; }\r\n  body[data-jsd-workbench] div:has(> span > [data-slot='conversation.hero.brand.mark']) { flex-direction: column; gap: 24px; font-size: 24px; line-height: 34px; font-weight: 600; }\r\n  body[data-jsd-workbench] .jsd-hero-mark { padding: 12px; border-radius: 24px; background: var(--dsw-alias-bg-layer-1); box-shadow: 0 8px 32px var(--jsd-composer-shadow); }\r\n  body[data-jsd-workbench] .jsd-hero-mark img { width: 48px; height: 48px; }\r\n  body[data-jsd-workbench] .jsd-starters { flex: none; margin-block: 0 16px; padding-bottom: 0; gap: 12px; }\r\n  .jsd-starters .jsd-welcome-description { display: block; grid-column: 1 / -1; margin: -4px 0 20px; color: var(--dsw-alias-label-secondary); font-size: 14px; line-height: 24px; text-align: center; }\r\n  body[data-jsd-workbench] .jsd-starters button { gap: 8px; min-height: 126px; padding: 18px; border-color: var(--dsw-alias-border-l1); border-radius: 14px; box-shadow: 0 3px 14px var(--jsd-composer-shadow); }\r\n  body[data-jsd-workbench] .jsd-starters button:hover:not(:disabled) { border-color: var(--dsw-alias-border-l4); background: var(--dsw-alias-bg-layer-1); box-shadow: 0 6px 24px var(--jsd-composer-shadow); }\r\n  .jsd-starters .jsd-starter-art { width: 30px; height: 30px; margin-bottom: 4px; }\r\n  body[data-jsd-workbench] [data-composer-card] { border-radius: 16px; }\r\n  body[data-jsd-workbench] [data-slot='sidebar.brand.mark'] .jsd-brand-mark { margin-inline-end: 8px; }\r\n  body[data-jsd-workbench] .jsd-brand-name { font-size: 14px; }\r\n  body[data-jsd-workbench] .jsd-page-title { font-size: 14px; font-weight: 500; color: var(--dsw-alias-label-secondary); }\r\n}\r\n\r\n@media (max-width: 767px) {\r\n  .jsd-starters { grid-template-columns: minmax(0, 1fr); gap: 8px; margin-block: 6px; }\r\n  .jsd-starters button { grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 1px 12px; padding: 12px 14px; }\r\n  .jsd-starter-art { grid-row: span 2; width: 30px; height: 30px; margin: 0; }\r\n  .jsd-page-title { font-size: 16px; margin-right: 8px; }\r\n  .jsd-resource { padding: 16px; }\r\n  .jsd-nav a { min-height: 44px; }\r\n}\r\n\r\n@media (prefers-reduced-motion: reduce) {\r\n  body[data-jsd-workbench] [data-composer-card], .jsd-nav a, .jsd-starters button { transition: none; }\r\n  .jsd-continue-spinner { animation: none; }\r\n}\r\n\r\n/* Source links stay ordinary native Markdown links and retain keyboard/menu behavior. */\r\nbody[data-jsd-workbench] a[href*=\"/api/v1/knowledge/documents/\"][href*=\"/content\"],\r\nbody[data-jsd-workbench] a[href*=\"/api/v1/agents/private-sources/\"][href*=\"/content\"],\r\n.jsd-source-link {\r\n  color: inherit;\r\n  font-size: inherit;\r\n  text-decoration: underline;\r\n  text-decoration-color: var(--dsw-alias-border-l2, #d4d4d4);\r\n  text-underline-offset: .2em;\r\n  overflow-wrap: anywhere;\r\n}\r\nbody[data-jsd-workbench] a[href*=\"/content\"]:hover, .jsd-source-link:hover {\r\n  text-decoration-color: currentColor;\r\n}\r\nbody[data-jsd-workbench] a[href*=\"/content\"]:focus-visible, .jsd-source-link:focus-visible {\r\n  outline: 2px solid currentColor;\r\n  outline-offset: 3px;\r\n  border-radius: 2px;\r\n}\r\n\r\n/* Citation chrome stays inline; the native message owns all layout and media. */\r\n.jsd-cited-answer { display: contents; }\r\nbody[data-jsd-workbench] .jsd-cited-answer a[data-jsd-citation],\r\nbody[data-jsd-workbench] a.jsd-source-link {\r\n  display: inline-block;\r\n  box-sizing: border-box;\r\n  max-width: min(100%, 28em);\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  color: var(--dsw-alias-label-secondary, inherit);\r\n  background: var(--dsw-alias-interactive-bg-hover, rgba(128,128,128,.12));\r\n  border-radius: 5px;\r\n  font-size: .78em;\r\n  font-weight: 400;\r\n  line-height: 1.65;\r\n  font-variant-numeric: tabular-nums;\r\n  white-space: nowrap;\r\n  vertical-align: -.3em;\r\n  text-decoration: none;\r\n  padding: 1px 7px;\r\n  margin-inline: 2px;\r\n}\r\nbody[data-jsd-workbench] :is(.jsd-cited-answer a[data-jsd-citation], a.jsd-source-link) > svg { display: none; }\r\nbody[data-jsd-workbench] :is(.jsd-cited-answer a[data-jsd-citation], a.jsd-source-link):hover { color: var(--dsw-alias-label-primary, inherit); text-decoration: none; }\r\nbody[data-jsd-workbench] :is(.jsd-cited-answer a[data-jsd-citation], a.jsd-source-link):focus-visible {\r\n  outline: 2px solid var(--dsw-alias-label-secondary, currentColor);\r\n  outline-offset: 2px;\r\n}\r\nbody[data-jsd-workbench] .jsd-briefing-period,\r\nbody[data-jsd-workbench] .jsd-briefing-entry-error {\r\n  font-size: 12px;\r\n  line-height: 1.7;\r\n  white-space: normal;\r\n  overflow-wrap: anywhere;\r\n}\r\n\r\n/* Spectrum uses native modal/composer seats and the workbench's existing tokens. */\r\n.jsd-spectrum-add { padding: 4px 8px; border-radius: 8px; font-size: 13px; line-height: 20px; white-space: nowrap; }\r\n.jsd-spectrum-add:hover { background: var(--dsw-alias-interactive-bg-hover); }\r\n.jsd-spectrum-dialog { width: min(760px, calc(100vw - 24px)); }\r\n.jsd-spectrum-picker { display: flex; flex-direction: column; gap: 14px; max-height: min(78vh, 780px); overflow: auto; padding: 4px 2px; }\r\n.jsd-spectrum-hint, .jsd-spectrum-chart small, .jsd-spectrum-pagination, .jsd-spectrum-footer > span { color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 1.7; }\r\n.jsd-spectrum-filters { display: flex; gap: 10px; }\r\n.jsd-spectrum-filters input, .jsd-spectrum-filters select { min-width: 0; border: 1px solid var(--dsw-alias-border-l2); border-radius: 8px; padding: 8px 10px; font: inherit; color: inherit; background: var(--dsw-alias-bg-layer-1); }\r\n.jsd-spectrum-filters input { flex: 1; }\r\n.jsd-spectrum-chart-list { min-height: 100px; max-height: 250px; overflow: auto; border-block: 1px solid var(--dsw-alias-border-l2); }\r\n.jsd-spectrum-chart { display: flex; align-items: flex-start; gap: 10px; padding: 10px 4px; cursor: pointer; }\r\n.jsd-spectrum-chart:hover { background: var(--dsw-alias-interactive-bg-hover); }\r\n.jsd-spectrum-chart > input { margin-top: 5px; flex-shrink: 0; }\r\n.jsd-spectrum-chart > span { min-width: 0; }\r\n.jsd-spectrum-chart strong, .jsd-spectrum-chart small { display: block; overflow-wrap: anywhere; }\r\n.jsd-spectrum-chart strong, .jsd-spectrum-selected strong { font-size: 13px; font-weight: 500; }\r\n.jsd-spectrum-pagination, .jsd-spectrum-selected-title, .jsd-spectrum-footer { display: flex; align-items: center; gap: 12px; }\r\n.jsd-spectrum-pagination > span:first-child, .jsd-spectrum-selected-title > strong, .jsd-spectrum-footer > span { flex: 1; }\r\n.jsd-spectrum-pagination button, .jsd-spectrum-selected-title button { padding: 3px 6px; font-size: 12px; border-radius: 5px; }\r\n.jsd-spectrum-selected { display: grid; gap: 12px; }\r\n.jsd-spectrum-selected-title > strong { min-width: 0; overflow-wrap: anywhere; }\r\n.jsd-spectrum-pages { display: flex; flex-wrap: wrap; gap: 8px 14px; max-height: 130px; overflow: auto; margin-top: 8px; }\r\n.jsd-spectrum-pages label { display: flex; align-items: center; gap: 5px; font-size: 12px; white-space: nowrap; }\r\n.jsd-spectrum-footer { padding-top: 12px; border-top: 1px solid var(--dsw-alias-border-l2); }\r\n.jsd-spectrum-footer button { border-radius: 8px; padding: 8px 14px; font-size: 13px; }\r\n.jsd-spectrum-error, .jsd-spectrum-entry-error { font-size: 13px; line-height: 1.7; color: var(--dsw-alias-label-primary); }\r\n.jsd-spectrum-original { display: block; width: 100%; height: auto; object-fit: contain; }\r\n.jsd-spectrum-facts { display: grid; grid-template-columns: auto minmax(0,1fr); gap: 8px 16px; font-size: 13px; line-height: 1.7; margin-block: 20px; }\r\n.jsd-spectrum-facts dt { color: var(--dsw-alias-label-secondary); }\r\n.jsd-spectrum-facts dd { margin: 0; overflow-wrap: anywhere; }\r\n.jsd-spectrum-source-page { margin: 24px 0; }\r\n.jsd-spectrum-source-page figcaption { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; margin-bottom: 10px; }\r\n.jsd-spectrum-source-page img { display: block; width: 100%; height: auto; border: 1px solid var(--dsw-alias-border-l2); border-radius: 6px; background: white; }\r\n.jsd-spectrum-picker button:disabled, .jsd-spectrum-add:disabled { opacity: .45; cursor: default; }\r\n@media (max-width: 600px) {\r\n  .jsd-spectrum-filters { flex-direction: column; }\r\n  .jsd-spectrum-picker { max-height: 75vh; }\r\n  .jsd-spectrum-footer { flex-wrap: wrap; }\r\n}\r\n\r\n.jsd-starter-owner { color: var(--dsw-alias-label-tertiary); font-size: 12px; line-height: 18px; }\r\n.jsd-starter-note { grid-column: 1 / -1; margin: 0; color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 20px; text-align: center; }\r\n.jsd-starters[data-task-starts] button { grid-template-columns: minmax(0, 1fr); }\r\n.jsd-expert-summary { cursor: pointer; color: var(--dsw-alias-label-secondary); font-size: 12px; }\r\n\r\n\r\n/* Business lists and expert selection share a compact, flat working surface. */\r\n.jsd-expert-grid { display: flex; flex-direction: column; gap: 0; border-top: 1px solid var(--dsw-alias-border-l1); }\r\n.jsd-expert-card {\r\n  display: grid;\r\n  grid-template-columns: minmax(200px, 240px) minmax(0, 1fr) auto;\r\n  column-gap: 20px;\r\n  row-gap: 6px;\r\n  padding: 16px 0;\r\n  border: 0;\r\n  border-bottom: 1px solid var(--dsw-alias-border-l1);\r\n  border-radius: 0;\r\n  box-shadow: none;\r\n}\r\n.jsd-expert-card-top { grid-column: 1; grid-row: 1 / 3; align-self: start; }\r\n.jsd-expert-card > p { grid-column: 2; margin: 0; min-height: 0; overflow-wrap: anywhere; }\r\n.jsd-expert-card .jsd-expert-tags { grid-column: 2; }\r\n.jsd-expert-card .jsd-expert-tags > span { padding: 0; background: transparent; font-size: 12px; }\r\n.jsd-expert-card .jsd-expert-tags > span + span::before { content: '·'; margin-right: 5px; }\r\n.jsd-expert-card-bottom { grid-column: 3; grid-row: 1 / 3; margin: 0; padding: 0; border: 0; align-self: center; }\r\n.jsd-expert-card .jsd-expert-avatar { width: 32px; height: 32px; border-radius: 0; background: transparent; }\r\n.jsd-expert-page-header input { max-width: 100%; }\r\n.jsd-expert-error { border: 0; border-left: 2px solid var(--dsw-alias-border-l2); background: transparent; overflow-wrap: anywhere; }\r\nbody[data-jsd-workbench] .jsd-starters button {\r\n  min-height: 68px;\r\n  padding: 12px;\r\n  border-radius: 6px;\r\n  border-color: var(--dsw-alias-border-l1);\r\n  box-shadow: none;\r\n}\r\nbody[data-jsd-workbench] .jsd-starters button:hover:not(:disabled) { box-shadow: none; }\r\nbody[data-jsd-workbench] .jsd-hero-mark { padding: 0; border-radius: 0; box-shadow: none; }\r\n@container (max-width: 720px) {\r\n  .jsd-expert-card { grid-template-columns: minmax(0, 1fr) auto; column-gap: 12px; }\r\n  .jsd-expert-card-top { grid-column: 1 / -1; grid-row: 1; }\r\n  .jsd-expert-card > p, .jsd-expert-card .jsd-expert-tags { grid-column: 1; }\r\n  .jsd-expert-card-bottom { grid-column: 2; grid-row: 2 / 4; }\r\n}\r\n@media (max-width: 767px) {\r\n  body[data-jsd-workbench] .jsd-starters button { min-height: 60px; }\r\n  .jsd-expert-card-bottom > .jsd-expert-button { min-height: 44px; }\r\n}\r\n"};
  const sourceLinks = (()=>{/** Presentation only: retain immutable destinations and use the native Markdown renderer. */
function shortSourceTitle(value = '') {
  return String(value).replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'')
    .replace(/_?sub_?(\d+)_?sub_?/gi,'$1').replace(/[_\s]+/g,' ').trim().slice(0,64) || '论文';
}
function originalAddress(value, origin) {
  try {
    const url=new URL(String(value).replace(/\\([&()])/g,'$1'),origin), id='[0-9a-f-]{36}';
    if(url.origin!==new URL(origin).origin) return null;
    if(new RegExp('^/api/v1/agents/business/images/SPECTRUM/'+id+'$').test(url.pathname)
      && url.searchParams.has('ref') && /^[1-9]\d*$/.test(url.searchParams.get('page')||'')) return url;
    if(new RegExp('^/api/v1/knowledge/documents/'+id+'/versions/'+id+'/content$').test(url.pathname)) return url;
    if(new RegExp('^/api/v1/agents/private-sources/'+id+'/content$').test(url.pathname)
      && new RegExp('^'+id+'$').test(url.searchParams.get('version_id')||'')) return url;
  } catch {}
  return null;
}
function labelText(value) {return String(value).replace(/[\[\]`\r\n]/g,' ').trim();}
function sourceLinkLabel(label, url) {
  const page=new URLSearchParams(url.hash.slice(1)).get('page');
  let topic=labelText(label).replace(/(?:\s*[·・]\s*)?(?:原文\s*[·・]?\s*)?第\s*\d+\s*页\s*$/,'').trim();
  if(!topic || /^(?:https?:|\/api\/|原文$|总入口$|同上链接$)/.test(topic)) topic='论文原文';
  topic=shortSourceTitle(topic);
  return topic+(page&&/^[1-9]\d*$/.test(page)?' · 第'+page+'页':'');
}
function normalizeOriginalLinks(text, origin='http://localhost', renderLink, renderOtherLink) {
  let base=null, fence=false;
  const address=String.raw`(?:jsd-resource:[A-Za-z0-9_-]{1,2048}|(?:https?:\/\/|\/(?:api\/v1|projects|experiments)\/)(?:\\[^\r\n]|[^\s<>\x60\x00()\\]|\((?:[^()\s]|\([^()\s]*\))*\))+)`;
  return String(text).split('\n').map(line=>{
    if(/^\s*(```|~~~)/.test(line)){fence=!fence;return line;}
    if(fence) return line;
    if(renderOtherLink && /^\s{0,3}\[(?:\\.|[^\]\\])+\]:/.test(line))return line;
    if(/^#{1,6}\s/.test(line)) base=null;
    // A new top-level literature entry starts a different source scope.
    if(/^[-*]\s/.test(line)&&!/#page=/.test(line)) base=null;
    const protectedLinks=[];
    const raw=value=>{const token='\u0000SOURCE'+protectedLinks.length+'\u0000';protectedLinks.push(value);return token;};
    const save=(label,url,title)=>raw(renderLink ? renderLink(label,url,title) : '['+labelText(label)+']('+url.href+')');
    // Images and inline programs are content, never bibliographic references.
    line=line.replace(/(`+)([^`]*?)\1|!\[(?:\\.|[^\]\\])*\]\((?:\\.|[^)\\])*\)/g,
      value=>/^`#page=[1-9]\d*`$/.test(value) ? value : raw(value));
    // A streaming Markdown destination must finish before it becomes a citation.
    line=line.replace(/\[(?:\\.|[^\]\\\n])*\]\([^\n)]*$/g,raw);
    const linkPattern=new RegExp(String.raw`\[((?:\\.|[^\]\\\n])*)\]\(\s*<?(${address})>?(?:[ \t]+(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'))?\s*\)`,'g');
    line=line.replace(linkPattern,(all,label,target,title,singleTitle)=>{
      const url=originalAddress(target,origin);
      if(!url)return raw(renderOtherLink?.(label,target,title||singleTitle) ?? all);
      if(!url.hash)base=url;
      return save(renderLink ? label : sourceLinkLabel(label,url),url,title||singleTitle);
    });
    if(renderOtherLink) line=line.replace(/<(https?:\/\/[^\s<>]+)>/g,(all,target)=>raw(renderOtherLink('',target) ?? all));
    line=line.replace(new RegExp(address,'g'),target=>{
      // Sentence punctuation is prose, not part of a bare URL. Explicit Markdown
      // destinations above retain their exact punctuation and query strings.
      const clean=renderOtherLink ? target.replace(/[.,;!?，。；！？、：）]+$/u,'') : target;
      const suffix=target.slice(clean.length);
      const url=originalAddress(clean,origin);
      if(!url)return raw((renderOtherLink?.('',clean) ?? clean)+suffix);
      if(!url.hash)base=url;
      return save(sourceLinkLabel('论文原文',url),url)+suffix;
    });
    line=line.replace(/([^；;\n]*?)[：:]\s*(?:同上链接\s*)?`?#page=([1-9]\d*)`?/g,(all,prefix,page)=>{
      // Keep list/indent structure outside the clickable description.
      const match=prefix.match(/^(\s*(?:[-*]\s+)?)(.*)$/), topic=match?.[2]?.trim();
      if(!base||!topic||topic.includes('\u0000'))return all;
      const url=new URL(base);url.hash='page='+page;
      return (match?.[1]||'')+save(topic+' · 第'+page+'页',url);
    });
    // An unbound fragment is not a navigable source: show a readable unresolved location.
    line=line.replace(/`?#page=([1-9]\d*)`?/g,(_all,page)=>'第'+page+'页（来源待确认）');
    return line.replace(/\u0000SOURCE(\d+)\u0000/g,(_all,index)=>protectedLinks[Number(index)]);
  }).join('\n');
}

// Only known, same-origin business routes may resolve a name through our API.
// External sites are labelled locally; displaying a citation never fetches them.
function describeSourceLink(label, target, origin='http://localhost', title, names=new Map()) {
  if(/^jsd-resource:[A-Za-z0-9_-]{1,2048}$/.test(target)) {
    return {kind:'business',url:target,title:labelText(title||label)||'业务资料',
      label:explicitCitationLabel(label,title)};
  }
  let url;
  try { url=new URL(String(target).replace(/\\([&()])/g,'$1'),origin); } catch { return null; }
  if(!/^https?:$/.test(url.protocol))return null;
  const project=url.origin===new URL(origin).origin
    && url.pathname.match(/^\/projects\/([a-f0-9-]{36})\/?$/i);
  const clean=value=>labelText(String(value||'').replace(/\\([\\[\]*_`])/g,'$1').replace(/\*\*|__/g,'')).replace(/\s+/g,' ').trim();
  const authored=clean(label);
  const meaningful=authored&&!/^(?:https?:\/\/|\/|\d+$|来源$|链接$)/i.test(authored);
  if(url.origin===new URL(origin).origin) {
    const id='[a-f0-9-]{36}';
    const record=new RegExp('^/api/v1/agents/business/records/[A-Z_]+/'+id+'$').test(url.pathname)&&url.searchParams.has('ref');
    const query=url.pathname==='/api/v1/agents/business/query'&&url.searchParams.has('q')&&url.searchParams.has('snapshot_hash');
    const compute=new RegExp('^/api/v1/agents/business/compute/[A-Za-z_-]+/'+id+'$').test(url.pathname)&&url.searchParams.has('revision');
    const experiment=new RegExp('^/experiments/'+id+'/?$').test(url.pathname);
    if(record||query||compute||experiment)return {kind:'business',url:url.href,
      title:clean(title)||(meaningful?authored:'业务资料'),label:meaningful?authored:'业务资料'};
  }
  if(project) {
    const endpoint='/api/v1/projects/'+project[1];
    const name=names.get(endpoint);
    return {kind:'project',url:url.href,endpoint,title:name|| (meaningful ? authored : '项目详情'),
      label:name ? '项目 · '+name : meaningful ? authored : '项目详情'};
  }
  // Leave unrelated internal API/file links and in-page navigation to native UI.
  if(url.origin===new URL(origin).origin || !/^https?:\/\//i.test(target))return null;
  const hostname=url.hostname.replace(/^www\./i,'');
  const sourceTitle=clean(title);
  return {kind:'web',url:url.href,title:sourceTitle|| (meaningful ? authored : hostname),
    label:meaningful ? authored : sourceTitle||hostname};
}

function markdownSourceLabel(value) {
  // A fetched business name is plain text, never Markdown instructions or HTML.
  return String(value).replace(/\s+/g,' ').replace(/[\\`*_{}\[\]<>!~|]/g,'\\$&');
}

function explicitCitationLabel(label, title) {
  // Labels come from the reference itself, never nearby prose, headings, or a
  // subject dictionary. Missing locator metadata cannot be invented by the UI.
  const clean=value=>labelText(String(value||'').replace(/\\([\\\[\]*_`])/g,'$1').replace(/\*\*|__/g,''))
    .replace(/(?:\s*[·・]\s*)?(?:第\s*\d+\s*页|p\s*\d+)\s*$/i,'').replace(/\s+/g,' ').trim();
  const useful=value=>value&&!/^(?:\d+|原文|论文|论文原文|来源|总入口|同上链接)$/.test(value)&&!/^(?:https?:|\/api\/)/.test(value);
  const named=clean(label), sourceTitle=clean(title);
  const value=useful(named)?named:useful(sourceTitle)?sourceTitle:'内容标签缺失';
  const limit=/[\u3400-\u9fff]/.test(value)?14:28;
  return value.length>limit ? value.slice(0,limit-1).trimEnd()+'…' : value;
}

/** A display projection only; stored answers and immutable evidence URLs are untouched. */
function projectSourceBlocks(blocks, origin='http://localhost', names=new Map()) {
  const sources=[], links=[], byDocument=new Map(), citations=[], otherCitations=[];
  const projected=blocks.map(block=>{
    if(block.kind!=='text') return block;
    const text=normalizeOriginalLinks(block.text,origin,(label,url,linkTitle)=>{
      const key=url.origin+url.pathname+(url.searchParams.has('version_id') ? '?version_id='+url.searchParams.get('version_id') : '');
      let source=byDocument.get(key);
      const title=labelText(String(linkTitle||label).replace(/\\([\\\[\]*_`])/g,'$1').replace(/\*\*|__/g,''))
        .replace(/(?:\s*[·・]\s*)?(?:原文\s*[·・]?\s*)?第\s*\d+\s*页\s*$/,'')
        .replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'').replace(/[_\s]+/g,' ').trim();
      const informative=title && !/^(?:\[?\d+\]?$|https?:|\/api\/|原文$|论文原文$|总入口$|同上链接$)/.test(title);
      if(!source){
        source={number:sources.length+1,title:informative ? title : '论文原文',locations:[]};
        byDocument.set(key,source);sources.push(source);
      } else if(informative && (source.title==='论文原文'||title.length>source.title.length)) source.title=title;
      if(!source.locations.some(item=>item.url===url.href)) {
        const page=new URLSearchParams(url.hash.slice(1)).get('page');
        source.locations.push({url:url.href,page:page&&/^[1-9]\d*$/.test(page) ? Number(page) : null});
      }
      const page=new URLSearchParams(url.hash.slice(1)).get('page');
      const topic=explicitCitationLabel(label,source.title);
      const tag=topic+(page&&/^[1-9]\d*$/.test(page) ? ' · p'+page : '');
      const token='\u0000CITE'+citations.length+'\u0000';
      citations.push({source,url:url.href,topic,tag});return token;
    },(label,target,title)=>{
      const link=describeSourceLink(label,target,origin,title,names);
      if(!link)return null;
      if(!links.some(item=>item.url===link.url))links.push(link);
      const token='\u0000LINK'+otherCitations.length+'\u0000';
      otherCitations.push({url:link.url,text:'['+markdownSourceLabel(explicitCitationLabel(link.label,link.title))+']('+target+')'});
      return token;
    });
    // Opaque retrieval IDs following a real citation are diagnostics, not prose.
    const readable=text.replace(/(\u0000CITE\d+\u0000)[ \t]*[（(]\s*`?q[a-f0-9]{24}`?(?:[、,，;；\s]+`?q[a-f0-9]{24}`?)*\s*[）)]/g,'$1')
      // Source-only parentheses are redundant around a chip. Ordinary prose,
      // scientific notation, programs and parentheses inside URLs stay intact.
      .replace(/\([ \t]*(\u0000(?:CITE|LINK)\d+\u0000(?:[ \t,，;；、]*\u0000(?:CITE|LINK)\d+\u0000)*)[ \t]*\)/g,'$1')
      .replace(/（[ \t]*(\u0000(?:CITE|LINK)\d+\u0000(?:[ \t,，;；、]*\u0000(?:CITE|LINK)\d+\u0000)*)[ \t]*）/g,'$1');
    // Only adjacent references collapse; all immutable targets remain in the
    // projection metadata and the stored answer retains the original references.
    const compact=readable.replace(/\u0000CITE\d+\u0000(?:[ \t]*(?:\n[ \t]*)?\u0000CITE\d+\u0000)*/g,run=>{
      const seen=new Set();
      return [...run.matchAll(/\u0000CITE(\d+)\u0000/g)].flatMap(match=>{
        const {source,url,tag}=citations[Number(match[1])],key=source.number+':'+tag;
        if(seen.has(key)) return [];
        seen.add(key);
        return ['['+labelText(tag)+']('+url+')'];
      }).join(' ');
    }).replace(/\u0000LINK\d+\u0000(?:[ \t]*\u0000LINK\d+\u0000)*/g,run=>{
      const seen=new Set();
      return [...run.matchAll(/\u0000LINK(\d+)\u0000/g)].flatMap(match=>{
        const citation=otherCitations[Number(match[1])];
        if(seen.has(citation.url))return [];
        seen.add(citation.url);return [citation.text];
      }).join(' ');
    });
    return compact===block.text ? block : {...block,text:compact};
  });
  return {blocks:projected,sources,links};
}
;return {normalizeOriginalLinks,sourceLinkLabel,shortSourceTitle,projectSourceBlocks};})();
  const browserCrypto = (()=>{/** Verify image bytes even on LAN HTTP, where SubtleCrypto is unavailable. */
function sha256Hex(buffer, cryptoApi = globalThis.crypto) {
  const hex = bytes => Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  if (typeof cryptoApi?.subtle?.digest === 'function') {
    return cryptoApi.subtle.digest('SHA-256', buffer).then(value => hex(new Uint8Array(value)));
  }
  // FIPS 180-4 SHA-256. This fallback is only for content integrity, not keys or signatures.
  const input = new Uint8Array(buffer);
  const padded = new Uint8Array(Math.ceil((input.length + 9) / 64) * 64);
  padded.set(input); padded[input.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor(input.length / 0x20000000));
  view.setUint32(padded.length - 4, (input.length * 8) >>> 0);
  const k = [
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
  ];
  const state = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
  const words = new Uint32Array(64);
  const rotate = (value, count) => (value >>> count) | (value << (32 - count));
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) words[i] = view.getUint32(offset + i * 4);
    for (let i = 16; i < 64; i++) {
      const x = words[i - 15], y = words[i - 2];
      words[i] = words[i - 16] + (rotate(x,7) ^ rotate(x,18) ^ (x >>> 3))
        + words[i - 7] + (rotate(y,17) ^ rotate(y,19) ^ (y >>> 10));
    }
    let [a,b,c,d,e,f,g,h] = state;
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (rotate(e,6) ^ rotate(e,11) ^ rotate(e,25)) + ((e & f) ^ (~e & g)) + k[i] + words[i]) >>> 0;
      const t2 = ((rotate(a,2) ^ rotate(a,13) ^ rotate(a,22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      h=g; g=f; f=e; e=(d+t1)>>>0; d=c; c=b; b=a; a=(t1+t2)>>>0;
    }
    [a,b,c,d,e,f,g,h].forEach((value, i) => {state[i] += value;});
  }
  return Array.from(state, value => value.toString(16).padStart(8, '0')).join('');
}

/** RFC 4122 v4 using secure random bytes, available on ordinary LAN HTTP. */
function generateBrowserUUID(cryptoApi = globalThis.crypto) {
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID();
  if (typeof cryptoApi?.getRandomValues !== 'function') throw new Error('当前浏览器不支持安全随机数生成，请升级浏览器后重试。');
  const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
;return {sha256Hex,generateBrowserUUID};})();
  const memoryUI = (()=>{const {generateBrowserUUID}=browserCrypto;

/** Browser settings and session controls. All authority is checked by Control. */
function registerMemoryUI(scope,{React,api,Switch,Modal,Toast}) {
  const h=React.createElement, {useState,useEffect,useRef,useSyncExternalStore}=React;
  const settingsListeners=new Set(), sessionListeners=new Set();
  let dialogSession=null, memoryAvailable;
  function showSessionMemory(sessionId) {
    dialogSession=sessionId;
    for(const listener of sessionListeners)listener();
  }
  const subscribeSession=listener=>{sessionListeners.add(listener);return()=>sessionListeners.delete(listener);};
  const call=async(body,signal)=>{
    let value;
    try {value=await api('memory',body,signal);}
    catch(e){if(e.code==='MEMORY_NOT_ENABLED')memoryAvailable=false;throw e;}
    memoryAvailable=true;
    if(['initialize','set-settings'].includes(body.action))for(const listener of settingsListeners)listener(value);
    return value;
  };
  const status={PENDING:'整理中',SUCCEEDED:'已保存',ACTIVE:'已保存',UNKNOWN:'待核对',FAILED:'未能完成',CANCELLED:'已停止'};
  const category={preference:'偏好',decision:'确认的决定',experience:'工作经验'};
  const errorMessage=error=>({MEMORY_REVISION_CHANGED:'记忆已被更新，请刷新后再编辑。',MEMORY_BUDGET_EXCEEDED:'今日记忆处理额度已用尽，作业会保留等待。',
    MEMORY_SOURCE_EXCLUDED:'该来源已停止参与记忆。',MEMORY_NOT_ENABLED:'记忆服务尚未启用。'}[error.code] || error.message || '暂时无法读取记忆，请稍后重试。');
  const action=(label,onClick,props={})=>h('button',{type:'button',onClick,...props,className:'jsd-memory-button '+(props.className || '')},label);
  const pending=value=>['PENDING','UNKNOWN'].includes(value);
  const dateLabel=value=>value && !Number.isNaN(Date.parse(value))
    ? new Intl.DateTimeFormat('zh-CN',{month:'short',day:'numeric'}).format(new Date(value)) : '';
  function Toggle({label,description,checked,onChange,disabled}) {
    return h('div',{className:'jsd-memory-switch'},h('div',{className:'jsd-memory-switch-copy'},h('strong',null,label),h('p',null,description)),
      h(Switch,{label,checked:!!checked,disabled,onChange,className:'jsd-memory-toggle'}));
  }
  async function openSource(entry) {
    const {source}=await call({action:'source',entry_id:entry.id,content_revision:entry.content_revision});
    if(source.session_id)await scope.uiWorkspace.openSession(source.session_id);
  }
  function MemorySection() {
    const [settings,setSettings]=useState(),[rows,setRows]=useState([]),[cursor,setCursor]=useState(null);
    const [busy,setBusy]=useState(false),[error,setError]=useState(''),[receipt,setReceipt]=useState();
    const [editing,setEditing]=useState(),[text,setText]=useState(''),[adding,setAdding]=useState(false),key=useRef();
    const alive=useRef(true),loading=useRef(false),pages=useRef(1);
    async function load(append=false,refresh=false) {
      if(loading.current)return;
      loading.current=true;setBusy(true);setError('');
      try {
        const prefs=await call({action:'initialize'});
        const entries=[];
        let next=append?cursor:null,read=0;
        do {
          const page=await call({action:'list',limit:20,...(next?{cursor:next}:{})});
          entries.push(...page.entries);next=page.next_cursor;read++;
        } while(refresh && next && read<pages.current);
        let updatedReceipt;
        if(receipt && ['PENDING','UNKNOWN'].includes(receipt.status)) {
          updatedReceipt=entries.find(entry=>entry.operation_id===receipt.operation_id)?.receipt
            || await call({action:'status',operation_id:receipt.operation_id});
        }
        if(alive.current) {
          pages.current=append?pages.current+1:read;
          setSettings(prefs);setRows(old=>append?[...old,...entries]:entries);setCursor(next);
          if(updatedReceipt)setReceipt(old=>old?.operation_id===updatedReceipt.operation_id?updatedReceipt:old);
        }
      } catch(e) {if(alive.current)setError(errorMessage(e));}
      finally {loading.current=false;if(alive.current)setBusy(false);}
    }
    useEffect(()=>{alive.current=true;void load();return()=>{alive.current=false;};},[]);
    useEffect(()=>{
      if(!rows.some(row=>['PENDING','UNKNOWN'].includes(row.receipt?.status)) && !['PENDING','UNKNOWN'].includes(receipt?.status))return;
      const timer=setTimeout(()=>void load(false,true),4000);return()=>clearTimeout(timer);
    },[rows,receipt]);
    async function change(patch) {
      setBusy(true);setError('');
      try {setSettings(await call({action:'set-settings',revision:settings.revision,patch}));}
      catch(e){setError(errorMessage(e));}finally{setBusy(false);}
    }
    async function mutate(body) {
      setBusy(true);setError('');
      try {
        if (body.action === 'remember') {
          key.current ||= generateBrowserUUID();
          body = {...body,idempotency_key:key.current};
        }
        const result=await call(body);setReceipt(result);setEditing(undefined);setAdding(false);setText('');key.current=undefined;
        await load();
      } catch(e){setError(errorMessage(e));}finally{setBusy(false);}
    }
    function add() {setEditing(undefined);setText('');setAdding(true);key.current=undefined;setError('');}
    function edit(entry) {setAdding(false);setEditing(entry);setText(entry.text);setError('');}
    function save(event) {
      event.preventDefault();
      return mutate(editing ? {action:'correct',entry_id:editing.id,revision:editing.revision,text}
        :{action:'remember',category:'preference',text});
    }
    function editor() {
      return h('form',{className:'jsd-memory-editor',onSubmit:save},
        h('label',null,editing?'更正记忆':'添加记忆',h('textarea',{value:text,onChange:e=>setText(e.target.value),required:true,maxLength:12000,rows:3,
          autoFocus:true,placeholder:'写下希望助手长期记住的工作偏好…','aria-label':editing?'更正记忆内容':'偏好内容'})),
        h('div',{className:'jsd-memory-editor-footer'},h('span',null,'保存后可随时更正或遗忘。'),
          h('div',null,action('取消',()=>{setEditing(undefined);setAdding(false);},{disabled:busy,className:'jsd-memory-text-button'}),
            h('button',{type:'submit',className:'jsd-memory-button jsd-memory-primary',disabled:busy || !text.trim()},busy?'保存中…':'保存记忆'))));
    }
    return h('div',{className:'jsd-memory-settings','data-ui':'memory'},
      h('header',{className:'jsd-memory-header'},h('div',null,h('h2',null,'个人记忆'),h('span',{className:'jsd-memory-privacy'},'仅本人可见')),
        h('p',null,'保存工作偏好与确认的决定，让之后的对话少一些重复。')),
      error && h('div',{role:'alert',className:'jsd-memory-feedback jsd-memory-error'},h('span',null,error),action('重试',()=>load(),{disabled:busy,className:'jsd-memory-text-button'})),
      !settings && !error && h('div',{className:'jsd-memory-loading',role:'status','aria-label':'正在读取记忆设置'},
        h('div',null,h('span'),h('span')),h('div',null,h('span'),h('span'))),
      settings && h(React.Fragment,null,
        h('section',{className:'jsd-memory-controls','aria-label':'记忆设置'},
          h(Toggle,{label:'读取个人记忆',description:'在新对话中参考已保存的偏好与历史。',checked:settings.read_enabled,disabled:busy,onChange:value=>change({read_enabled:value})}),
          h(Toggle,{label:'自动整理记忆',description:'整理新对话中的偏好和决定，离开页面后继续。',checked:settings.auto_enabled,disabled:busy,onChange:value=>change({auto_enabled:value})})),
        !settings.notice_seen && h('aside',{className:'jsd-memory-notice'},h('p',null,'两项开关独立生效，记忆随时可更正或遗忘。'),
          action('知道了',()=>change({notice_seen:true}),{disabled:busy,className:'jsd-memory-text-button'})),
        h('section',{className:'jsd-memory-collection','aria-label':'已保存的记忆'},
          h('div',{className:'jsd-memory-list-heading'},h('div',null,h('h3',null,'已保存的记忆'),
            h('span',{className:'jsd-memory-count'},rows.length+(cursor?' +':'')+' 条')),
            action('添加记忆',add,{disabled:busy || adding || !!editing,className:adding || editing?'':'jsd-memory-primary'})),
          receipt && h('div',{role:'status',className:'jsd-memory-feedback jsd-memory-receipt','data-state':receipt.status},
            h('span',null,h('span',{className:'jsd-memory-status-dot','aria-hidden':true}),receipt.summary,
              receipt.usage_pending && h('small',null,'用量待核对')),
            pending(receipt.status) && action('检查进度',async()=>{
              try {setReceipt(await call({action:'status',operation_id:receipt.operation_id}));await load();}catch(e){setError(errorMessage(e));}
            },{disabled:busy,className:'jsd-memory-text-button'})),
          adding && editor(),
          !rows.length && !adding && h('div',{className:'jsd-memory-empty'},
            h('h4',null,'把常用的工作约定留在这里'),h('p',null,'添加一条偏好，或在对话中告诉助手需要长期记住的事。'),
            h('p',{className:'jsd-memory-example'},'例如：研发回答先列结论，再列依据。')),
          h('ul',{className:'jsd-memory-list'},rows.map(entry=>h('li',{key:entry.id,className:'jsd-memory-entry','data-editing':editing?.id===entry.id || undefined},
            h('div',{className:'jsd-memory-entry-body'},
              h('div',{className:'jsd-memory-entry-heading'},h('span',null,category[entry.category]),
                h('span',{className:'jsd-memory-status','data-state':entry.receipt?.status || entry.status},
                  h('span',{className:'jsd-memory-status-dot','aria-hidden':true}),status[entry.receipt?.status || entry.status] || '待核对')),
              editing?.id===entry.id ? editor() : h('p',{className:'jsd-memory-entry-text'},entry.text || '正在整理，完成后即可查看。'),
              h('div',{className:'jsd-memory-entry-meta'},
                entry.source.session_id ? action('原始会话'+(entry.source.turn?' · 第 '+entry.source.turn+' 轮':''),()=>openSource(entry).catch(e=>setError(errorMessage(e))),
                  {disabled:busy || entry.status!=='ACTIVE',className:'jsd-memory-source jsd-memory-text-button','aria-label':'查看原始会话'})
                  : h('span',null,'本人添加'),
                dateLabel(entry.updated_at) && h('time',{dateTime:entry.updated_at,title:new Date(entry.updated_at).toLocaleString('zh-CN')},dateLabel(entry.updated_at)))),
            editing?.id!==entry.id && h('div',{className:'jsd-memory-entry-actions'},
              action('编辑',()=>edit(entry),{disabled:busy || !entry.text,className:'jsd-memory-text-button'}),
              action('遗忘',()=>mutate({action:'forget',entry_id:entry.id,revision:entry.revision}),{disabled:busy,className:'jsd-memory-text-button jsd-memory-danger'}))))),
          cursor && action('加载更多',()=>load(true),{disabled:busy,className:'jsd-memory-more'}))));
  }
  function FirstUseNotice() {
    const [prefs,setPrefs]=useState(),[dismissed,setDismissed]=useState(false),saving=useRef(false);
    useEffect(()=>{
      settingsListeners.add(setPrefs);
      const controller=new AbortController();
      void call({action:'initialize'},controller.signal).catch(()=>{});
      return()=>{controller.abort();settingsListeners.delete(setPrefs);};
    },[]);
    if(!prefs || prefs.notice_seen || dismissed || (!prefs.read_enabled && !prefs.auto_enabled))return null;
    async function notice(disable) {
      if(saving.current)return;
      saving.current=true;
      try {
        await call({action:'set-settings',revision:prefs.revision,patch:{notice_seen:true,...(disable?{read_enabled:false,auto_enabled:false}:{})}});
        if(disable)scope.contextMenus.notify('已关闭个人记忆',true);
      } catch(e){setDismissed(true);scope.contextMenus.notify(errorMessage(e),false);}
      finally{saving.current=false;}
    }
    return h(Toast,{text:'个人记忆会保留工作偏好和确认的决定，供之后的会话参考。',holdMs:10000,onDone:()=>setDismissed(true),
      actions:[{label:'知道了',prefix:' ',onClick:()=>notice(false)},{label:'关闭个人记忆',prefix:' · ',onClick:()=>notice(true)}]});
  }
  function SessionMemory({sessionId}) {
    const [value,setValue]=useState(),[prefs,setPrefs]=useState(),[error,setError]=useState(''),[busy,setBusy]=useState(false);
    const [attempt,retry]=useState(0);
    useEffect(()=>{
      const controller=new AbortController();
      setError('');
      settingsListeners.add(setPrefs);
      void Promise.all([call({action:'initialize'},controller.signal),call({action:'session-settings',session_id:sessionId},controller.signal)])
        .then(([,settings])=>{if(!controller.signal.aborted)setValue(settings);})
        .catch(e=>{if(!controller.signal.aborted)setError(errorMessage(e));});
      return()=>{controller.abort();settingsListeners.delete(setPrefs);};
    },[sessionId,attempt]);
    async function update(patch) {
      setBusy(true);setError('');
      try {setValue(await call({action:'set-session',session_id:sessionId,patch}));}
      catch(e){setError(errorMessage(e));}finally{setBusy(false);}
    }
    const ready=value && prefs;
    return h(Modal,{open:true,title:'本会话记忆',closeLabel:'关闭',description:'更改只对这个会话生效。',onClose:()=>showSessionMemory(null),backdropBlur:false},
      h('div',{className:'jsd-session-memory','data-ui':'session-memory'},
        !ready && !error && h('p',{role:'status'},'正在读取会话设置…'),
        ready && h('div',null,
          h(Toggle,{label:'本会话读取记忆',description:prefs.read_enabled?'允许使用本人已授权的记忆。':'全局读取已关闭。',checked:prefs.read_enabled && value.read_enabled,disabled:busy || !prefs.read_enabled,onChange:read_enabled=>update({read_enabled})}),
          h(Toggle,{label:'本会话参与整理',description:prefs.auto_enabled?'关闭后，本会话不再产生新增记忆。':'全局自动整理已关闭。',checked:value.participate,disabled:busy,onChange:participate=>update({participate})})),
        error && h('div',{role:'alert',className:'jsd-memory-feedback jsd-memory-error'},h('span',null,error),
          !ready && action('重试',()=>retry(count=>count+1),{className:'jsd-memory-text-button'}))));
  }
  function MemoryOverlay() {
    const sessionId=useSyncExternalStore(subscribeSession,()=>dialogSession);
    return h(React.Fragment,null,h(FirstUseNotice),sessionId && h(SessionMemory,{key:sessionId,sessionId}));
  }
  function MemoryIcon() {
    return h('svg',{width:16,height:16,viewBox:'0 0 16 16',fill:'none',stroke:'currentColor',
      strokeWidth:1.25,strokeLinecap:'round',strokeLinejoin:'round','aria-hidden':true},
      h('rect',{x:4,y:2.5,width:9,height:11,rx:1.5}),
      h('path',{d:'M2.75 5h2.5m-2.5 3h2.5m-2.5 3h2.5M7.5 6h3m-3 3h2'}));
  }
  scope.slots.inject('settings.section',()=>scope.slots.register({name:'settings.section',id:'memory',order:20,label:()=> '记忆'},MemorySection));
  scope.slots.inject('shell.overlay',()=>scope.slots.register({name:'shell.overlay',id:'personal-memory'},MemoryOverlay));
  scope.slots.inject('contextMenu.icon',()=>scope.slots.register({name:'contextMenu.icon',key:'enterprise.personal-memory'},MemoryIcon));
  scope.slots.inject('settings.section.icon',()=>scope.slots.register({name:'settings.section.icon',id:'memory'},MemoryIcon));
  for(const target of ['sessionHeader','session'])scope.effect(()=>scope.contextMenus.register({
    id:'enterprise.personal-memory',target,order:400,
    resolve:({sessionId})=>sessionId && memoryAvailable!==false ? [{id:'settings',label:'本会话记忆',
      icon:{key:'enterprise.personal-memory'},run:()=>showSessionMemory(sessionId)}] : [],
  }));
}
;return {registerMemoryUI};})();
  const briefingEntry = (()=>{/** Validate only navigation identifiers; authorization remains with Control. */
function readBriefingEntry(address) {
  const url = new URL(address, 'http://local');
  const kind = url.searchParams.get('briefing');
  const run_id = url.searchParams.get('run');
  if (!['project-morning','research-weekly','experiment-review'].includes(kind)
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(run_id || '')) return null;
  return {kind,run_id};
}
;return {readBriefingEntry};})();
  const expertsUI = (()=>{/** WorkBuddy-style discovery on native main/menu/modal seats; execution stays native. */
function registerExpertsUI(scope,{React,api,Modal,Menu,MenuItemButton,tasks}) {
  const h=React.createElement,{useState,useEffect,useSyncExternalStore}=React;
  const PANEL='enterprise-experts',listeners=new Set();
  let dialog=null;
  const subscribe=listener=>{listeners.add(listener);return()=>listeners.delete(listener);};
  const show=value=>{dialog=value;for(const listener of listeners)listener();};
  const action=(label,onClick,props={})=>h('button',{type:'button',...props,onClick,
    className:'jsd-expert-button '+(props.className || '')},label);
  const feedback=error=>({EXPERT_REQUIRES_NEW_SESSION:'请新建会话使用其他专家。',
    SYS_NOT_FOUND:'此专家已不可用，请刷新专家中心。',OPERATION_FORBIDDEN:'此专家已停用，请选择其他专家。'}[error.code] || error.message);
  function ExpertIcon({size=18}) {
    return h('svg',{width:size,height:size,viewBox:'0 0 20 20',fill:'none',stroke:'currentColor',strokeWidth:1.4,'aria-hidden':true},
      h('rect',{x:3,y:5,width:14,height:11,rx:3}),h('path',{d:'M10 2v3M6 16v2m8-2v2M1 9v3m18-3v3M7 12h6'}),
      h('circle',{cx:7,cy:9,r:.6}),h('circle',{cx:13,cy:9,r:.6}));
  }
  function Avatar({expert,small=false}) {
    const paths={
      'spectrum-assistant':[['path',{d:'M3 3v18h18M5 17l3-2 2 1 3-11 3 13 2-6 3-1'}]],
      'project-assistant':[['rect',{x:3.5,y:5,width:17,height:15,rx:2.5}],['path',{d:'M8 3v4m8-4v4M4 10h16M8 14h2m-2 3h2m4-3h2m-2 3h2'}]],
      'customer-assistant':[['path',{d:'M7 16.5 3.5 20v-5A8 8 0 0 1 3 12c0-4.4 4-8 9-8s9 3.6 9 8-4 8-9 8c-1.2 0-2.4-.2-3.5-.6'}],['circle',{cx:12,cy:9.5,r:2}],['path',{d:'M8 16c.6-2.6 7.4-2.6 8 0'}]],
      'experiment-assistant':[['path',{d:'M9 3h6m-5 0v6l-5.5 9.2A2.5 2.5 0 0 0 6.7 22h10.6a2.5 2.5 0 0 0 2.2-3.8L14 9V3M8 15h8'}],['circle',{cx:10,cy:18,r:.7,fill:'currentColor',stroke:'none'}],['circle',{cx:14.5,cy:19,r:.7,fill:'currentColor',stroke:'none'}]],
      'quality-assistant':[['path',{d:'m12 2 8 3.5v6c0 4.6-3.3 8-8 10.5-4.7-2.5-8-5.9-8-10.5v-6L12 2Z'}],['path',{d:'m8 11.5 2.7 2.7L16 9'}]],
      'production-assistant':[['path',{d:'M3 21V10l6 3V8l6 3V4h4l2 17H3Z'}],['path',{d:'M7 17h1m4 0h1m4 0h1M15 7h4'}]],
      'materials-advisor':[['path',{d:'m12 3 7.8 4.5v9L12 21l-7.8-4.5v-9L12 3Zm0 0v7m7.8-2.5-6 3.5m6 5.5-6-3.5M12 21v-7m-7.8 2.5 6-3.5m-6-5.5 6 3.5'}],['circle',{cx:12,cy:12,r:2}]],
      'technical-assistant':[['path',{d:'M12 5.5C8 2.8 4.5 3.5 3 4v15c3.3-1 6-.5 9 1.5 3-2 5.7-2.5 9-1.5V4c-1.5-.5-5-1.2-9 1.5Zm0 0v15M6.5 8l2 .5m-2 3 2 .5m7-3.5 2-.5m-2 4 2-.5'}]],
      'data-assistant':[['rect',{x:3,y:3,width:18,height:18,rx:3}],['path',{d:'M3 9h18M9 9v12m6-12v12M3 15h18M7 6h.01m3 0h.01'}]],
    };
    return h('span',{className:'jsd-expert-avatar'+(small?' jsd-expert-avatar-small':''),'data-expert':expert.id,'aria-hidden':true},
      h('svg',{viewBox:'0 0 24 24',fill:'none',stroke:'currentColor',strokeWidth:1.65,strokeLinecap:'round',strokeLinejoin:'round'},
        (paths[expert.id] || paths['project-assistant']).map(([tag,props],index)=>h(tag,{key:index,...props}))));
  }
  function PinIcon({pinned}) {
    return h('svg',{width:16,height:16,viewBox:'0 0 20 20',fill:pinned?'currentColor':'none',stroke:'currentColor',strokeWidth:1.2,'aria-hidden':true},
      h('path',{d:'m10 2 2.5 5.1 5.6.8-4 4 .9 5.6-5-2.7-5 2.7.9-5.6-4-4 5.6-.8Z'}));
  }
  function useExpert(sessionId) {
    const [state,setState]=useState({sessionId:null,expert:null,loading:true}),[attempt,retry]=useState(0);
    useEffect(()=>{
      const controller=new AbortController();
      setState({sessionId,expert:null,loading:true});
      api('experts',{action:'session',session_id:sessionId},controller.signal)
        .then(value=>{if(!controller.signal.aborted)setState({sessionId,expert:value.expert,loading:false});})
        .catch(error=>{if(!controller.signal.aborted)setState({sessionId,expert:null,loading:false,error:feedback(error)});});
      return()=>controller.abort();
    },[sessionId,attempt]);
    return {...(state.sessionId===sessionId?state:{expert:null,loading:true}),retry:()=>retry(n=>n+1)};
  }
  function useCatalog() {
    const [catalog,setCatalog]=useState(null),[error,setError]=useState(''),[attempt,retry]=useState(0);
    useEffect(()=>{
      const controller=new AbortController();
      setError('');
      api('experts',{action:'list'},controller.signal).then(value=>{if(!controller.signal.aborted)setCatalog(value);})
        .catch(error=>{if(!controller.signal.aborted)setError(feedback(error));});
      return()=>controller.abort();
    },[attempt]);
    return {catalog,setCatalog,error,setError,retry:()=>retry(n=>n+1)};
  }
  async function summon(expert,prompt) {
    const value=await api('experts',{action:'summon',expert_id:expert.id});
    const reference=scope.sessions.retain(value.session_id,{source:'enterprise-expert'});
    try {
      const binding=await reference.ready;
      scope.uiWorkspace.openSession(value.session_id);
      if(prompt && (!binding || scope.conversation.input.requestDraftInitialization(binding,
        {prompt,whenDraftExists:'append'})!=='applied')) throw new Error('专家会话已创建，示例未填入，请打开会话后重试。');
    } finally {reference.release();}
    show(null);
  }
  function ErrorNotice({error,retry,busy}) {
    return error && h('div',{role:'alert',className:'jsd-expert-error'},error,' ',action('重试',retry,{disabled:busy}));
  }
  function WorkConfig({config,skills}) {
    if(!config)return null;
    const {scope:workScope,checks,delivery,pending}=config;
    const names={'project-diagnosis':'项目诊断','meeting-actions':'会议行动项整理','work-briefing':'工作简报编制',
      'customer-requirements':'客户需求整理与比较','business-reply':'业务回复起草','record-validation':'记录整理与核验',
      'experiment-comparison':'实验对比','quality-review':'检测质量核对与文件草稿','production-reconciliation':'生产库存与数量核对',
      'formulation-analysis':'配方分析','research-design':'竞争假设与实验设计','literature-review':'文献调研与研读',
      'technical-comparison':'论文专利与技术路线比较','table-preparation':'表格整理','jsd-experiment-converter':'实验记录标准化转换','spectrum-analysis':'图谱解读与来源核对'};
    return h('section',{className:'jsd-expert-config','aria-label':'默认工作设置'},
      h('h4',null,'默认工作设置'),
      h('dl',null,
        h('dt',null,'工作范围'),h('dd',null,workScope.domains.join('、'),h('p',null,workScope.selection)),
        h('dt',null,'共享技能'),h('dd',null,skills.map(name=>names[name] || name).join('、')),
        h('dt',null,'重点核验'),h('dd',null,checks.required_fields.map(field=>field.label).join('、') || '按实际业务模板核验'),
        Object.keys(checks.unit_targets).length>0 && h(React.Fragment,null,
          h('dt',null,'默认单位'),h('dd',null,Object.values(checks.unit_targets).join('、'))),
        h('dt',null,'检查规则'),h('dd',null,h('ul',null,checks.rules.map(text=>h('li',{key:text},text)))),
        h('dt',null,'默认交付'),h('dd',null,delivery.sections.join('＋'),
          h('p',null,'平台通用结构 · 草稿，按当前任务选用。')),
        pending.length>0 && h(React.Fragment,null,h('dt',null,'待提供'),h('dd',null,pending.join('；')))),
      h('p',{className:'jsd-expert-note'},'公司统一维护。未提供的标准会标记待确认。'));
  }
  function ExpertDetail({expert,sessionId}) {
    const [busy,setBusy]=useState(false),[error,setError]=useState('');
    const lastPrompt=React.useRef();
    async function start(prompt) {
      lastPrompt.current=prompt;
      setBusy(true);setError('');
      try {await summon(expert,prompt);}catch(error){setError(feedback(error));}finally{setBusy(false);}
    }
    return h(Modal,{open:true,title:'专家详情',closeLabel:'关闭专家详情',className:'jsd-expert-dialog',
      contentClassName:'jsd-expert-content',backdropBlur:false,onClose:()=>{if(!busy)show(null);}},
      h('article',{className:'jsd-expert-detail','data-expert-detail':true},
        h('div',{className:'jsd-expert-identity'},h(Avatar,{expert}),h('div',null,h('h3',null,expert.name),h('p',null,'杰事达 · 公司专家')),
          action(busy?'正在准备…':expert.enabled?'召唤专家':'已停用',()=>start(),{disabled:busy || !expert.enabled,className:'jsd-expert-primary'})),
        h('p',{className:'jsd-expert-summary'},expert.summary),
        h('div',{className:'jsd-expert-tags'},expert.capabilities.map(text=>h('span',{key:text},text))),
        h('div',{className:'jsd-expert-meta'},h('span',null,expert.category)),
        h(ErrorNotice,{error,retry:()=>start(lastPrompt.current),busy}),
        h('h4',null,'专家帮你做'),h('p',{className:'jsd-expert-note'},'选择一个任务示例，填入草稿后即可开始。'),
        h('div',{className:'jsd-expert-examples'},expert.examples.map(text=>
          action(h(React.Fragment,null,h('span',null,text),h('span',{'aria-hidden':true},'↗')),()=>start(text),
            {key:text,disabled:busy || !expert.enabled,title:'新建专家会话并填入可编辑草稿'}))),
        h(WorkConfig,{config:expert.work_config,skills:expert.skills}),
        h('details',{className:'jsd-expert-role'},h('summary',null,'专家职责'),h('p',null,expert.instructions)),
        h('p',{className:'jsd-expert-note'},sessionId?'使用其他专家会新开会话，当前草稿和附件会保留。':'可使用你获准访问的资料和主动上传的文件。')));
  }
  function ExpertCenter() {
    const {catalog,setCatalog,error,setError,retry}=useCatalog();
    const [query,setQuery]=useState(''),[category,setCategory]=useState('全部'),[view,setView]=useState('全部');
    const [busy,setBusy]=useState(false);
    async function pin(expert) {
      setBusy(true);setError('');
      try {setCatalog(await api('experts',{action:'pin',expert_id:expert.id,pinned:!catalog.pinned.includes(expert.id)}));}
      catch(error){setError(feedback(error));}finally{setBusy(false);}
    }
    async function start(expert) {
      setBusy(true);setError('');
      try {await summon(expert);}catch(error){setError(feedback(error));}finally{setBusy(false);}
    }
    const categories=['全部',...new Set((catalog?.experts || []).map(row=>row.category))];
    const ids=view==='常用'?catalog?.pinned:view==='最近'?catalog?.recent:null;
    const ordered=ids?ids.map(id=>catalog.experts.find(row=>row.id===id)).filter(Boolean):(catalog?.experts || []);
    const rows=ordered
      .filter(row=>category==='全部' || row.category===category)
      .filter(row=>[row.name,row.summary,...row.capabilities].join(' ').toLowerCase().includes(query.trim().toLowerCase()));
    return h('section',{className:'jsd-expert-page','data-expert-center':true},
      h('header',{className:'jsd-expert-page-header'},h('h1',null,h(ExpertIcon,{size:22}),'专家中心'),
        h('input',{type:'search',value:query,onChange:e=>setQuery(e.target.value),placeholder:'搜索专家','aria-label':'搜索专家'})),
      h('div',{className:'jsd-experts'},
        h('div',{className:'jsd-expert-heading'},h('div',null,h('h2',null,'公司专家'),h('p',null,'找到懂你业务的专家，把工作交给它。')),
          h('div',{className:'jsd-expert-views',role:'group','aria-label':'专家列表'},['全部','常用','最近'].map(name=>
            action(name,()=>setView(name),{key:name,'aria-pressed':view===name})))),
        h('div',{className:'jsd-expert-categories',role:'group','aria-label':'专业领域'},categories.map(name=>
          action(name,()=>setCategory(name),{key:name,'aria-pressed':category===name}))),
        h(ErrorNotice,{error,retry,busy}),
        !catalog ? h('p',{role:'status'},error?'专家列表暂时不可用。':'正在加载公司专家…') : !rows.length ? h('div',{className:'jsd-expert-empty'},
          h('p',null,query?'没有匹配的专家，请试试其他关键词。':view==='常用'?'还没有常用专家。':view==='最近'?'使用过的专家会显示在这里。':'此领域暂无专家。'),
          action('浏览全部专家',()=>{setView('全部');setCategory('全部');setQuery('');})) :
        h('div',{className:'jsd-expert-grid'},rows.map(expert=>h('article',{key:expert.id,className:'jsd-expert-card'},
          h('div',{className:'jsd-expert-card-top'},
            action(h(React.Fragment,null,h(Avatar,{expert}),h('span',null,h('strong',null,expert.name),h('small',null,expert.category))),
              ()=>show({expert}),{className:'jsd-expert-card-title','aria-label':'查看 '+expert.name+' 详情',disabled:busy}),
            action(h(PinIcon,{pinned:catalog.pinned.includes(expert.id)}),()=>pin(expert),{disabled:busy,'aria-pressed':catalog.pinned.includes(expert.id),
              'aria-label':(catalog.pinned.includes(expert.id)?'取消常用：':'设为常用：')+expert.name,className:'jsd-expert-pin'})),
          h('p',null,expert.summary),
          h('div',{className:'jsd-expert-tags'},expert.capabilities.slice(0,2).map(text=>h('span',{key:text},text))),
          h('div',{className:'jsd-expert-card-bottom'},
            action(expert.enabled?'召唤':'已停用',()=>start(expert),{disabled:busy || !expert.enabled})))))));
  }
  function Overlay() {
    const current=useSyncExternalStore(subscribe,()=>dialog);
    return current && h(ExpertDetail,{key:current.expert.id,...current});
  }
  function QuickPicker({expert,sessionId,onClose}) {
    const {catalog,error,retry}=useCatalog(),[query,setQuery]=useState(''),[busy,setBusy]=useState(false),[failure,setFailure]=useState('');
    const all=catalog?.experts || [],term=query.trim().toLowerCase();
    const ids=catalog?[...new Set([...catalog.pinned,...catalog.recent])]:[];
    const rows=term?all.filter(row=>[row.name,row.summary,...row.capabilities].join(' ').toLowerCase().includes(term)):
      (ids.length?ids.map(id=>all.find(row=>row.id===id)).filter(Boolean):all).slice(0,6);
    async function start(row) {
      if(row.id===expert?.id){onClose();return;}
      setBusy(true);setFailure('');
      try {await summon(row);onClose();}catch(error){setFailure(feedback(error));}finally{setBusy(false);}
    }
    return h('div',{className:'jsd-expert-quick'},
      h('input',{type:'search',value:query,onChange:e=>setQuery(e.target.value),placeholder:'搜索专家','aria-label':'快速搜索专家',
        onKeyDown:e=>{if(e.key!=='Escape' && e.key!=='ArrowDown' && e.key!=='ArrowUp' && e.key!=='Tab')e.stopPropagation();}}),
      h(ErrorNotice,{error:error || failure,retry:()=>{setFailure('');retry();},busy}),
      !catalog && !error && h('p',{role:'status'},'正在加载专家…'),
      catalog && !rows.length && h('p',{role:'status'},'没有匹配的专家'),
      rows.map(row=>h(MenuItemButton,{key:row.id,icon:h(Avatar,{expert:row,small:true}),current:row.id===expert?.id,
        disabled:busy || !row.enabled,onSelect:()=>start(row)},row.name,catalog.pinned.includes(row.id)?' · 常用':'')),
      expert && h(MenuItemButton,{onSelect:()=>{onClose();show({expert,sessionId});}},'查看当前专家详情'),
      h(MenuItemButton,{separatorBefore:true,onSelect:()=>{onClose();scope.layout.selectPanel(PANEL);}},'↗ 召唤更多专家'),
      sessionId && h('p',{className:'jsd-expert-note'},'切换专家会新开会话'));
  }
  function Selector({sessionId}) {
    const {expert,error,retry,loading}=useExpert(sessionId),[open,setOpen]=useState(false);
    return h('div',{className:'jsd-expert-selector'},
      h(Menu,{open,onClose:()=>setOpen(false),side:'top',portal:true,autoFocus:false,listClassName:'jsd-expert-quick-menu',
        anchor:action(h(React.Fragment,null,expert?h(Avatar,{expert,small:true}):h(ExpertIcon),h('span',null,expert?.name || '专家'),h('span',{'aria-hidden':true},'⌄')),
          ()=>setOpen(!open),{disabled:loading,'aria-label':expert?'当前专家：'+expert.name+'，选择专家':'选择专家','aria-expanded':open})},
        open && h(QuickPicker,{expert,sessionId,onClose:()=>setOpen(false)})),
      expert?.enabled===false && h('span',{role:'status'},'已停用'),
      error && action('重试',retry,{title:error}));
  }
  function ExpertWelcome({sessionId,useSession,fillPrompt}) {
    const blank=useSession(value=>value.blank),{expert}=useExpert(sessionId),[notice,setNotice]=useState('');
    if(!blank || !expert)return null;
    return h('details',{className:'jsd-expert-welcome','aria-label':expert.name+' 的任务示例'},
      h('summary',{className:'jsd-expert-summary'},'当前专家：'+expert.name+' · 查看任务示例'),
      h('p',null,expert.summary),
      h('div',{className:'jsd-expert-examples'},expert.examples.map(text=>action(h(React.Fragment,null,h('span',null,text),h('span',{'aria-hidden':true},'↗')),
        ()=>setNotice(fillPrompt(text)?'已填入草稿，确认后发送。':'输入框正忙，请稍后再试。'),
        {key:text,disabled:expert.enabled===false,title:'填入可编辑草稿'}))),
      notice && h('p',{role:'status'},notice));
  }
  scope.slots.inject('main',()=>scope.slots.register({name:'main',key:PANEL},ExpertCenter));
  scope.slots.inject('sidebar.panellist',()=>scope.slots.register({name:'sidebar.panellist',id:PANEL,order:-20,label:()=> '专家中心'},ExpertIcon));
  scope.slots.inject('shell.overlay',()=>scope.slots.register({name:'shell.overlay',id:PANEL},Overlay));
  scope.slots.inject('conversation.input.left',()=>scope.slots.register({name:'conversation.input.left',id:'enterprise-expert-selector',order:-20,
    inject:sessionId=>({sessionId}),
  },Selector));
  scope.slots.inject('conversation.input.dock',()=>scope.slots.register({name:'conversation.input.dock',id:'enterprise-expert-welcome',order:-30,
    inject:sessionId=>({sessionId,fillPrompt:prompt=>{
      const binding=scope.sessions.binding(sessionId);
      if(!binding || scope.conversation.input.requestDraftInitialization(binding,{prompt,whenDraftExists:'append'})!=='applied')return false;
      scope.conversation.input.for(scope.sessions.scope(sessionId)).focus();return true;
    }}),
  },ExpertWelcome));
  return {useExpert,startTask:id=>tasks.startWorkbenchTask(id,summon)};
}
;return {registerExpertsUI};})();
  const tasks = (()=>{/** Task entries reuse existing expert identities and never install schedules. */
const workbenchTasks = Object.freeze([
  {id:'experiment-records',title:'整理实验资料',description:'整理配方、工艺和结果，对照原件检查缺项。',expertId:'experiment-assistant',expertName:'数据整理助理',prompt:'请整理我提供的实验资料，按实验和样品保留配方、工艺、检测结果及来源。标准模板按现有映射读取，复杂文本只提出有依据的解释；缺失和歧义单独列出，不补造数据。先确认资料和交付方式，结果仍待逐份人工核对，不自动确认、审核或发布。'},
  {id:'tables',title:'整理普通表格',description:'梳理字段、编号和单位，定位重复与冲突。',expertId:'experiment-assistant',expertName:'数据整理助理',prompt:'请整理我提供的普通表格，先确认字段含义、编号、单位、主键和目标格式，保留原始值与来源，列明重复、缺失和冲突。不要静默合并冲突或覆盖原件；需要保存时沿用已有授权能力与真实回执。'},
  {id:'materials',title:'分析实验与设计验证',description:'诊断材料问题，比较证据，按需设计验证实验。',expertId:'materials-advisor',expertName:'材料研发助理',prompt:'请结合我提供的实验、配方、工艺或材料问题开展分析。先明确目标和比较基准，核对样品、版本及制备和测试条件是否可比，区分事实、原因假设与建议；按问题需要提出有判据的验证方案，不强制每次都设计实验。未知条件留空，不编造实测，不自动创建或发布实验。'},
  {id:'experiment-review',title:'实验复盘',description:'复盘指定实验的变化、结论、局限与下一步。',expertId:'materials-advisor',expertName:'材料研发助理',prompt:'请对我指定的实验做一次复盘分析，不创建或修改定时任务。先明确实验范围和固定版本，再读取配方、工艺、检测条件与结果，核对可比性，说明哪些结论有证据支持、哪些仍受缺项或混杂因素影响，提出下一步建议。没有指定实验时先询问范围；不把预测或建议当实测，不修改、审核或发布原实验。'},
].map(task=>Object.freeze(task)));

function startWorkbenchTask(id,summon) {
  const task=workbenchTasks.find(item=>item.id===id);
  if(!task)throw new Error('未知任务，请刷新工作台。');
  return summon({id:task.expertId},task.prompt);
}
;return {workbenchTasks,startWorkbenchTask};})();
  const spectrumUI = (()=>{const {sha256Hex}=browserCrypto;

/** Recover selections from the live semantic draft, including after session navigation. */
function spectrumDraftSelection(draft, attachments = []) {
  const selected = new Map(), owned = new Set();
  for (const occurrence of draft.references || draft.occurrences || []) {
    let value;
    try {value = JSON.parse(occurrence.ref);} catch {continue;}
    const ref = value?.ref;
    if (ref?.kind !== 'SPECTRUM' || typeof ref.resource_id !== 'string' || !Array.isArray(ref.pages)) continue;
    let item = selected.get(ref.resource_id);
    if (!item) {item = {id:ref.resource_id, versionId:ref.version_id, pages:[], files:{}}; selected.set(item.id, item);}
    for (const page of ref.pages) if (!item.pages.includes(page)) item.pages.push(page);
    for (const attachment of attachments) {
      const declared = Array.isArray(value.spectrumFiles) && value.spectrumFiles.find(file => file?.id === attachment.id && ref.pages.includes(file.page));
      // References created before ownership metadata used this exact generated suffix.
      const page = declared?.page ?? ref.pages.find(page => attachment.file?.name?.endsWith('-' + ref.resource_id.slice(0,8) + '-第' + page + '页.png'));
      if (page !== undefined) {item.files[page] = attachment.id; owned.add(attachment.id);}
    }
  }
  return {selection:[...selected.values()], attachmentIds:[...owned]};
}

/** Replace only spectrum chips; keep typed questions, other sources, quotes and commands. */
function replaceSpectrumReferences(draft, references) {
  const removed = draft.references.filter(item => {
    try {return JSON.parse(item.ref)?.ref?.kind === 'SPECTRUM';} catch {return false;}
  });
  const inserted = references.map(item => item.clipboardText).join(' ');
  const shift = offset => offset + removed.reduce((sum, item, index) => sum +
    (item.offset < offset ? (index === 0 ? inserted.length : 0) - item.length : 0), 0);
  let text = draft.text, insertion = removed[0]?.offset;
  if (removed.length) {
    for (let index=removed.length-1; index>=0; index--) {
      const item = removed[index];
      text = text.slice(0,item.offset) + (index === 0 ? inserted : '') + text.slice(item.offset + item.length);
    }
  } else if (inserted) {
    if (text && !/\s$/.test(text)) text += ' ';
    insertion = text.length; text += inserted + ' ';
  }
  const kept = draft.references.filter(item => !removed.includes(item)).map(item => ({...item, offset:shift(item.offset)}));
  const quotes = draft.quotes?.map(item => ({...item, offset:shift(item.offset)}));
  for (const item of references) {
    kept.push({...item, offset:insertion, length:item.clipboardText.length});
    insertion += item.clipboardText.length + 1;
  }
  return {...draft, text, references:kept.sort((a,b) => a.offset-b.offset), ...(quotes ? {quotes} : {})};
}

/** A source is a readable original page, with the fields needed to interpret it. */
function SpectrumResourceBody({React, value}) {
  const h = React.createElement, facts = value.spectrum || {};
  const fields = [['样品', facts.sampleName], ['批号', facts.batchNo], ['测试条件', facts.testConditions], ['原文件', facts.originalName]]
    .filter(([, value]) => typeof value === 'string' && value.trim());
  function Page({item}) {
    const [failed, setFailed] = React.useState(false), url = item.source.anchor.read_path;
    return h('figure', {className:'jsd-spectrum-source-page'},
      h('figcaption', null, '原图第 ' + item.source.anchor.page_no + ' 页', h('a', {href:url}, '放大查看')),
      failed ? h('p', {role:'alert'}, '页面暂时无法显示，请重新打开或检查访问权限。') :
        h('a', {href:url, 'aria-label':'放大查看第 ' + item.source.anchor.page_no + ' 页'},
          h('img', {src:url, alt:value.title + '，原始第 ' + item.source.anchor.page_no + ' 页', onError:() => setFailed(true)})));
  }
  return h('article', {className:'jsd-resource jsd-spectrum-source'}, h('h2', null, facts.title || value.title),
    h('p', {className:'jsd-spectrum-hint'}, '分析引用的原始图谱，可核对页面内容与测试条件。'),
    fields.length > 0 && h('dl', {className:'jsd-spectrum-facts'}, fields.flatMap(([label, text]) => [h('dt',{key:label},label),h('dd',{key:label+'-value'},text)])),
    (value.candidates || []).map(item => h(Page, {key:item.source.anchor.page_no, item})),
    h('a', {href:'/spectrum/view?chartId=' + value.ref.resource_id, target:'_blank', rel:'noopener'}, '在图谱中心查看'));
}

/** Spectrum selection enriches the native composer; submission and history stay native. */
function registerSpectrumUI(scope, {React, api, Modal, useExpert, marker}) {
  const h = React.createElement, {useEffect, useState} = React;
  const limit = 20;
  let entry = null;
  try {
    const value = new URL(location.href).searchParams.get('spectrum');
    if (value !== null) {
      const ids = value ? value.split(',') : [];
      if (ids.length > 12 || ids.some(id => !/^[a-f0-9-]{36}$/i.test(id))) throw new Error('INVALID_SPECTRUM_SELECTION');
      entry = [...new Set(ids)];
    }
  } catch { entry = []; }
  let launch, launched = null;
  const listeners = new Set();
  const subscribe = fn => {listeners.add(fn); return () => listeners.delete(fn);};
  const snapshot = () => launched;
  async function business(path, signal) {
    const response = await fetch('/api/v1/spc/' + path, {credentials:'same-origin', signal, cache:'no-store'});
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.success === false) throw new Error(result.message || '图谱暂不可读取，请检查访问权限后重试。');
    return result.data;
  }
  const button = (label, onClick, props = {}) => h('button', {type:'button', onClick, ...props,
    className:'jsd-expert-button ' + (props.className || '')}, label);

  function Entry() {
    const [error, setError] = useState(''), [attempt, retry] = useState(0);
    useEffect(() => {
      if (entry === null || launched) return;
      let alive = true;
      launch ||= api('experts', {action:'summon', expert_id:'spectrum-assistant'}).catch(error => {launch = null; throw error;});
      launch.then(async value => {
        if (!alive) return;
        launched = {sessionId:value.session_id, ids:entry}; listeners.forEach(fn => fn());
        entry = null;
        const url = new URL(location.href); url.searchParams.delete('spectrum');
        history.replaceState(history.state, '', url.pathname + url.search + url.hash);
        await scope.uiWorkspace.openSession(value.session_id);
      }).catch(error => {if (alive) setError(error.message);});
      return () => {alive = false;};
    }, [attempt]);
    return error && h('div', {className:'jsd-spectrum-entry-error', role:'alert'}, error,
      button('重试', () => {setError(''); retry(value => value + 1);}));
  }

  function Picker({sessionId, initialSelection, onClose}) {
    const [rows, setRows] = useState([]), [selected, setSelected] = useState({});
    const [query, setQuery] = useState(''), [category, setCategory] = useState(''), [categories, setCategories] = useState([]);
    const [page, setPage] = useState(1), [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true), [initializing, setInitializing] = useState(true);
    const [busy, setBusy] = useState(false), [error, setError] = useState(''), [attempt, retry] = useState(0);
    const initialized = React.useRef(false);
    useEffect(() => {
      const controller = new AbortController();
      Promise.all([business('categories', controller.signal), Promise.all(initialSelection.map(item => business('charts/' + item.id, controller.signal)))])
        .then(([groups, charts]) => {
          if (controller.signal.aborted) return;
          setCategories(groups || []);
          if (!initialized.current) {
            const changed = charts.some(chart => initialSelection.find(item => item.id === chart.id)?.versionId && initialSelection.find(item => item.id === chart.id).versionId !== chart.sha256);
            setSelected(Object.fromEntries(charts.filter(chart => {
              const previous = initialSelection.find(item => item.id === chart.id);
              return !previous.versionId || previous.versionId === chart.sha256;
            }).map(chart => [chart.id, {chart, pages:initialSelection.find(item => item.id === chart.id).pages}])));
            if (changed) setError('部分图谱原文件已更新，请重新勾选后确认。');
            initialized.current = true;
          }
          setInitializing(false);
        }).catch(error => {if (!controller.signal.aborted) {setError(error.message); setInitializing(false);}});
      return () => controller.abort();
    }, [initialSelection, attempt]);
    useEffect(() => {
      const controller = new AbortController(); setLoading(true);
      const timer = setTimeout(() => {
        const params = new URLSearchParams({keyword:query, status:'READY', page:String(page), size:'30'});
        if (category) params.set('categoryId', category);
        business('charts?' + params, controller.signal).then(value => {
          if (!controller.signal.aborted) {setRows(value.items || []); setTotal(value.total || 0); setLoading(false);}
        }).catch(error => {if (!controller.signal.aborted) {setError(error.message); setLoading(false);}});
      }, 180);
      return () => {clearTimeout(timer); controller.abort();};
    }, [query, category, page, attempt]);
    const count = Object.values(selected).reduce((sum, value) => sum + value.pages.length, 0);
    const updatePages = (id, pages) => setSelected(current => ({...current, [id]:{...current[id], pages}}));
    const toggle = chart => {
      setError('');
      setSelected(current => {
        if (current[chart.id]) {const next = {...current}; delete next[chart.id]; return next;}
        if (Object.keys(current).length >= 12) {setError('一次最多选择 12 张图谱。'); return current;}
        return {...current, [chart.id]:{chart, pages:[1]}};
      });
    };
    async function attach() {
      if (busy || initializing || count > limit || Object.values(selected).some(item => !item.pages.length)) return;
      setBusy(true); setError('');
      let drafts = [], reference;
      try {
        reference = scope.sessions.retain(sessionId, {source:'spectrum-selection'});
        const binding = await reference.ready;
        if (!binding) throw new Error('工作台会话已关闭，请重新打开后添加。');
        const shell = scope.conversation.input.shellFor(binding);
        if (shell.state.getSnapshot().phase !== 'plain') throw new Error('请等待当前消息发送完成后添加图谱。');
        const before = shell.state.getSnapshot();
        const previous = spectrumDraftSelection(shell.draftSnapshot, scope.conversation.resolveDraftAttachments(before.attachmentIds));
        if (before.attachmentIds.length - previous.attachmentIds.length + count > limit) throw new Error('图片和附件合计最多 20 个，请减少选择。');
        const files = [], resources = [], fileOwners = [], reused = new Set();
        for (const {chart, pages} of Object.values(selected)) {
          const value = await api('resource', {ref:{kind:'SPECTRUM', resource_id:chart.id, version_id:chart.sha256, pages:[...pages].sort((a,b) => a-b)}});
          const resource = {value, spectrumFiles:[]}; resources.push(resource);
          for (const candidate of value.candidates) {
            const page = candidate.source.anchor.page_no;
            const retained = previous.selection.find(item => item.id === chart.id && item.versionId === chart.sha256)?.files[page];
            if (retained) {resource.spectrumFiles.push({page,id:retained}); reused.add(retained); continue;}
            const url = candidate.source.anchor.read_path;
            const response = await fetch(url, {credentials:'same-origin', cache:'no-store'});
            if (!response.ok || !response.headers.get('content-type')?.startsWith('image/png')) throw new Error('图谱页面读取失败，请重新选择后重试。');
            const bytes = await response.arrayBuffer();
            const hash = await sha256Hex(bytes);
            if (hash !== response.headers.get('X-Content-SHA256')) throw new Error('图谱图片校验失败，请重试。');
            const title = chart.title.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 80);
            files.push(new File([bytes], title + '-' + chart.id.slice(0,8) + '-第' + candidate.source.anchor.page_no + '页.png', {type:'image/png'}));
            fileOwners.push({resource,page});
          }
        }
        const current = shell.state.getSnapshot();
        if (scope.sessions.binding(sessionId) !== binding || current.phase !== 'plain' || current.draftRev !== before.draftRev
            || current.attachmentIds.join() !== before.attachmentIds.join()) throw new Error('问题或附件已变化，请重新打开图谱选择后确认。');
        drafts = scope.conversation.createDrafts(sessionId, files, true);
        drafts.forEach((draft,index) => fileOwners[index].resource.spectrumFiles.push({page:fileOwners[index].page,id:draft.id}));
        const refs = resources.map(({value,spectrumFiles}) => ({source:'企业资料',
          ref:JSON.stringify({ref:value.ref, label:value.title, spectrumFiles}), label:value.title,
          appearance:'file', clipboardText:marker(value.title,value.ref)}));
        const original = shell.draftSnapshot;
        shell.setDraft(replaceSpectrumReferences(original, refs));
        if (!shell.addAttachments(drafts.map(item => item.id))) {shell.setDraft(original); throw new Error('输入框暂时不可编辑，请稍后重试。');}
        for (const id of previous.attachmentIds) if (!reused.has(id)) {shell.removeAttachment(id); scope.conversation.releaseDraftAttachment(id);}
        drafts = []; shell.focus(); onClose();
      } catch (error) {setError(error.message);}
      finally {
        for (const draft of drafts) scope.conversation.releaseDraftAttachment(draft.id);
        reference?.release(); setBusy(false);
      }
    }
    return h(Modal, {open:true, title:'选择图谱与页面', closeLabel:'关闭图谱选择', className:'jsd-spectrum-dialog',
      contentClassName:'jsd-spectrum-picker', backdropBlur:false, onClose:() => {if (!busy) onClose();}},
      h('p', {className:'jsd-spectrum-hint'}, '已选图谱与当前问题同步。可调整页码或取消勾选，确认后更新问题中的图片。'),
      h('div', {className:'jsd-spectrum-filters'},
        h('input', {type:'search', placeholder:'搜索图谱、样品或批号', 'aria-label':'搜索图谱', value:query, disabled:busy,
          onChange:event => {setQuery(event.target.value); setPage(1);}}),
        h('select', {'aria-label':'图谱分类', value:category, disabled:busy, onChange:event => {setCategory(event.target.value); setPage(1);}},
          h('option', {value:''}, '全部分类'), categories.map(item => h('option', {key:item.id, value:item.id}, item.name)))),
      error && h('div', {role:'alert', className:'jsd-expert-error jsd-spectrum-error'}, error, ' ', button('重试', () => {setError(''); retry(value => value + 1);}, {disabled:busy})),
      h('div', {className:'jsd-spectrum-chart-list', 'aria-label':'可选图谱', 'aria-busy':loading},
        loading ? h('p', {role:'status'}, '正在读取图谱…') : !rows.length ? h('p', null, '没有符合条件的图谱。') : rows.map(chart =>
          h('label', {key:chart.id, className:'jsd-spectrum-chart'},
            h('input', {type:'checkbox', checked:Boolean(selected[chart.id]), disabled:busy || initializing, onChange:() => toggle(chart)}),
            h('span', null, h('strong', null, chart.title), h('small', null, [chart.categoryName, chart.sampleName, chart.batchNo, chart.pageCount + ' 页'].filter(Boolean).join(' · ')))))),
      h('div', {className:'jsd-spectrum-pagination'}, h('span', null, total + ' 张图谱'),
        button('上一页', () => setPage(value => value-1), {disabled:busy || page <= 1}), h('span', null, page),
        button('下一页', () => setPage(value => value+1), {disabled:busy || page*30 >= total})),
      h('div', {className:'jsd-spectrum-selected', 'aria-label':'已选图谱页面'}, Object.values(selected).map(({chart, pages}) =>
        h('section', {key:chart.id}, h('div', {className:'jsd-spectrum-selected-title'}, h('strong', null, chart.title),
          button('移除', () => toggle(chart), {disabled:busy, 'aria-label':'移除 ' + chart.title})),
        h('div', {className:'jsd-spectrum-pages', role:'group', 'aria-label':chart.title + ' 页码'},
          Array.from({length:chart.pageCount}, (_,index) => index+1).map(number => h('label', {key:number},
            h('input', {type:'checkbox', checked:pages.includes(number), disabled:busy || (!pages.includes(number) && (pages.length >= 5 || count >= limit)),
              onChange:() => updatePages(chart.id, pages.includes(number) ? pages.filter(value => value !== number) : [...pages, number])}), '第 ' + number + ' 页')))))),
      h('footer', {className:'jsd-spectrum-footer'}, h('span', null, Object.keys(selected).length + ' 张图谱 · ' + count + ' / 20 页'),
        button(busy ? '正在准备图谱…' : '确认选择', () => void attach(), {className:'jsd-expert-primary', disabled:busy || initializing || (!count && !initialSelection.length) || count > limit || Object.values(selected).some(item => !item.pages.length)})));
  }
  function Selector({sessionId, useInput}) {
    const {expert} = useExpert(sessionId), input = useInput(value => value);
    const current = React.useSyncExternalStore(subscribe, snapshot, snapshot);
    const [open, setOpen] = useState(false), [initialSelection, setInitialSelection] = useState([]);
    const selected = spectrumDraftSelection(input).selection;
    useEffect(() => {
      if (current?.sessionId === sessionId) {setInitialSelection(current.ids.map(id => ({id,pages:[1]}))); setOpen(true); launched = null; listeners.forEach(fn => fn());}
    }, [current, sessionId]);
    if (expert?.id !== 'spectrum-assistant') return null;
    return h(React.Fragment, null,
      button(selected.length ? '已选 ' + selected.length + ' 张图谱' : '添加图谱', () => {setInitialSelection(selected); setOpen(true);}, {className:'jsd-spectrum-add', disabled:input.phase !== 'plain', 'aria-label':'添加图谱并选择页面'}),
      open && h(Picker, {sessionId, initialSelection, onClose:() => setOpen(false)}));
  }
  scope.slots.inject('shell.overlay', () => scope.slots.register({name:'shell.overlay', id:'enterprise-spectrum-entry'}, Entry));
  scope.slots.inject('conversation.input.left', () => scope.slots.register({name:'conversation.input.left', id:'enterprise-spectrum-picker', order:-15,
    inject:sessionId => ({sessionId})}, Selector));
}
;return {registerSpectrumUI,SpectrumResourceBody};})();
  const base = '/agent/';
  async function api(action, body, signal) {
    const response = await fetch(base + 'business/' + action, { method: 'POST',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal });
    if (!response.ok) {
      const value=await response.json().catch(()=>({}));
      throw Object.assign(new Error(response.status===409 ? '内容或设置已变化，请刷新后重试。' : '暂时无法读取，请检查登录和资料权限。'),{code:value.error});
    }
    return response.json();
  }
  const button = (label, onClick, more = {}) => h('button', { type: 'button', onClick, ...more }, label);
  function registerBriefingNavigation(scope) {
    const request=briefingEntry.readBriefingEntry(location.href);
    let entry=null;
    const listeners=new Set();
    const subscribe=callback=>{listeners.add(callback);return ()=>listeners.delete(callback);};
    const snapshot=()=>entry;
    function Launch() {
      const [error,setError]=useState(false);
      useEffect(()=>{
        if(!request)return;
        const controller=new AbortController();
        api('briefing-open',request,controller.signal).then(value=>{
          if(controller.signal.aborted)return;
          entry=value;listeners.forEach(callback=>callback());
          scope.uiWorkspace.openSession(value.session_id);
        }).catch(()=>{if(!controller.signal.aborted)setError(true);});
        return ()=>controller.abort();
      },[]);
      return h('div',null,h('h1',{className:'jsd-page-title'},'AI工作台'),
        error && h('span',{role:'alert',className:'jsd-briefing-entry-error'},'该期简报暂不可用，请返回工作台刷新。'));
    }
    function Period({useSession}) {
      const selected=useSession(value=>value.sessionId);
      const value=React.useSyncExternalStore(subscribe,snapshot,snapshot);
      if(!value || selected!==value.session_id)return null;
      const date=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'numeric',day:'numeric'}).format(new Date(value.window.end));
      // Native Chat owns the turn rail; no second reader or DOM-driven scrolling.
      return h('span',{className:'jsd-briefing-period',role:'status'},
        '所选'+value.title+' · '+date+' · 请通过会话轮次导航查看第 '+value.turn+' 轮');
    }
    scope.slots.inject('conversation.header.leading',()=>scope.slots.register({name:'conversation.header.leading',id:'enterprise-page-title',order:-10},Launch));
    scope.slots.inject('conversation.input.right',()=>scope.slots.register({name:'conversation.input.right',id:'enterprise-briefing-period'},Period));
  }
  function returnDestination() {
    try {
      const value = sessionStorage.getItem('jsd.ai-workbench.return') || document.referrer;
      if (value) {
        const url = new URL(value, location.origin);
        // Navigation only: resource access is still checked by the business page.
        if (url.origin === location.origin && /^\/(?:dashboard|partners|templates|projects|experiments|research-test|quality|spectrum|production-orders|knowledge|data|inventory|system)(?:\/|$)/.test(url.pathname)) {
          const record = /^\/(projects|experiments)\/[0-9a-f]{8}-[0-9a-f-]{27}(?:\/|$)/i.exec(url.pathname);
          return {href:url.pathname+url.search+url.hash,label:record ? record[1] === 'projects' ? '返回项目' : '返回实验' : '返回系统'};
        }
      }
    } catch { /* A missing or invalid source must not prevent returning to the system. */ }
    return {href:'/dashboard',label:'返回系统'};
  }
  function Navigation({ wide }) {
    const {IconChevronLeftOutlineRegular} = require('@deepseek-ai/dsh-client-ui-primitives');
    const {href,label} = returnDestination();
    return h('nav', { className: 'jsd-nav', 'data-wide':String(wide !== false), 'aria-label': '返回业务系统' },
      h('a',{href,title:label,'aria-label':label},
        h(IconChevronLeftOutlineRegular,{size:16}),wide !== false && h('span',null,label)));
  }
  function BrandMark({size,className = ''}) {
    return h('span',{className:'jsd-brand-mark '+className,'aria-hidden':true},
      h('img',{src:base+'favicon.svg',width:size,height:size,alt:'',draggable:false}));
  }
  function HeroMark({size,className = ''}) {
    return h(BrandMark,{size,className:'jsd-hero-mark '+className});
  }
  const HeroContext = React.createContext(false);
  function HeroFrame({hero,children}) {
    return h(HeroContext.Provider,{value:hero},children);
  }
  function StarterArt({kind}) {
    const pearl = 'var(--dsw-alias-bg-layer-3)', peach = 'var(--dsw-specific-sidebar-nav-item-active)';
    const stroke = 'var(--dsw-alias-state-business-primary)';
    return h('svg',{viewBox:'0 0 40 40',fill:'none','aria-hidden':true},
      kind==='read' ? h(React.Fragment,null,
        h('path',{d:'M11 5h13l7 7v21a3 3 0 0 1-3 3H11a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3Z',fill:pearl,stroke}),
        h('path',{d:'M24 5v8h7',fill:peach,stroke}),
        h('path',{d:'M13 18h12m-12 5h12m-12 5h7',stroke,strokeWidth:1.5,strokeLinecap:'round'})) :
      kind==='compare' ? h(React.Fragment,null,
        h('rect',{x:4,y:8,width:14,height:24,rx:4,fill:pearl,stroke}),
        h('rect',{x:22,y:8,width:14,height:24,rx:4,fill:peach,stroke}),
        h('path',{d:'M9 15h4m-4 5h4m14-5h4m-4 5h4',stroke,strokeWidth:1.5,strokeLinecap:'round'}),
        h('path',{d:'m17 24 3 3 3-3',stroke,strokeWidth:1.4,strokeLinecap:'round',strokeLinejoin:'round'})) :
      h(React.Fragment,null,
        h('rect',{x:5,y:20,width:8,height:14,rx:3,fill:peach,stroke}),
        h('rect',{x:16,y:6,width:8,height:28,rx:3,fill:pearl,stroke}),
        h('rect',{x:27,y:13,width:8,height:21,rx:3,fill:peach,stroke}),
        h('path',{d:'M19 10v19M30 17v12M8 24v5',stroke,strokeOpacity:.6,strokeLinecap:'round'})));
  }
  function registerWelcomeCards(scope,{useExpert,startTask}) {
    function WelcomeCards({sessionId,useInput}) {
      const hero=React.useContext(HeroContext),phase=useInput(value=>value.phase);
      const {loading}=useExpert(sessionId);
      const [busy,setBusy]=useState(''),[error,setError]=useState('');
      const pending=useRef(false);
      if(!hero || loading)return null;
      async function start(task) {
        if(pending.current)return;
        pending.current=true;setBusy(task.id);setError('');
        try {await startTask(task.id);}
        catch(failure){setError(failure.message || '暂时无法开始任务，请稍后重试。');}
        finally{pending.current=false;setBusy('');}
      }
      return h('section',{className:'jsd-starters','data-task-starts':true,'aria-label':'研发任务'},
        h('p',{className:'jsd-welcome-description'},'先选任务，再添加资料，确认草稿后发送。'),
        tasks.workbenchTasks.map(task=>h('button',{key:task.id,type:'button','aria-label':task.title,
          title:'新开对应专家会话，填入可编辑草稿，不自动发送',disabled:phase!=='plain' || Boolean(busy),
          onClick:()=>start(task),'aria-busy':busy===task.id},
          h('strong',null,task.title),h('span',{className:'jsd-starter-description'},task.description),
          h('span',{className:'jsd-starter-owner'},busy===task.id?'正在准备…':'由'+task.expertName+'处理'))),
        error && h('p',{role:'alert',className:'jsd-starter-note'},error),
        h('p',{className:'jsd-starter-note'},'自动复盘在“自动化任务”中查看；也可从侧栏“专家中心”选择专家。'));
    }
    scope.slots.inject('conversation.hero.frame',()=>scope.slots.register({
      name:'conversation.hero.frame',id:'enterprise-welcome',
    },HeroFrame));
    scope.slots.inject('conversation.input.dock',()=>scope.slots.register({
      name:'conversation.input.dock',id:'enterprise-welcome-cards',order:-20,
      inject:sessionId=>({sessionId}),
    },WelcomeCards));
  }
  const encode = value => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const decode = token => JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(token.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0))));
  const marker = (label, value) => '[' + label.replace(/[\[\]\n]/g, ' ').slice(0, 240) + '](jsd-resource:' + encode(value) + ')';
  const addressFor = value => 'dsh-resource://enterprise/' + encode(value);
  const refOnly = value => {const {only, ...ref} = value; return ref;};
  const originalPdfSlot = '@jsd-aird/original.pdf';
  const nativePdfKey = '@deepseek-ai/dsh-client-ui-sidebar-documentpreview/pdf';
  // Only immutable, same-origin source endpoints enter this adapter. Content
  // authorization remains in Java; the PDF renderer and sidebar belong to dsh.
  function originalUrl(value) {
    try {
      const url = new URL(value, location.origin), id = '[0-9a-f-]{36}';
      if (url.origin !== location.origin) return null;
      const privateSource = new RegExp('^/api/v1/agents/private-sources/'+id+'/content$').test(url.pathname)
        && new RegExp('^'+id+'$').test(url.searchParams.get('version_id') || '');
      const publishedSource = new RegExp('^/api/v1/knowledge/documents/'+id+'/versions/'+id+'/content$').test(url.pathname);
      const spectrumPage = new RegExp('^/api/v1/agents/business/images/SPECTRUM/'+id+'$').test(url.pathname)
        && url.searchParams.has('ref') && /^[1-9]\d*$/.test(url.searchParams.get('page') || '');
      return privateSource || publishedSource || spectrumPage ? url : null;
    } catch { return null; }
  }
  function OriginalBody({useTabInfo, renderSlot}) {
    const {tab} = useTabInfo(), root = useRef(null);
    const url = originalUrl(decode(tab.navigation.address.split('/').at(-1)));
    const address = url?.href, page = Number(new URLSearchParams(url?.hash.slice(1)).get('page')) || 1;
    const [content, setContent] = useState(null), [error, setError] = useState('');
    useEffect(() => {
      const controller = new AbortController(); let imageUrl; setContent(null); setError('');
      if (!address) {setError('原文地址无效。'); return;}
      fetch(address, {signal:controller.signal, cache:'no-store', credentials:'same-origin'})
        .then(async response => {
          if (!response.ok) throw new Error('无法读取原文，请检查当前权限和版本。');
          const mime = response.headers.get('content-type')?.split(';')[0];
          if (mime === 'image/png') {
            const blob = await response.blob();
            if (!controller.signal.aborted) {imageUrl = URL.createObjectURL(blob); setContent({kind:'image',url:imageUrl});}
            return;
          }
          if (mime !== 'application/pdf') throw new Error('此原文格式请使用原文件入口打开。');
          const data = new Uint8Array(await response.arrayBuffer());
          if (!controller.signal.aborted) setContent({kind:'bytes',data});
        }).catch(e => {if (!controller.signal.aborted) setError(e.message);});
      return () => {controller.abort(); if (imageUrl) URL.revokeObjectURL(imageUrl);};
    }, [address]);
    useEffect(() => {
      if (!content || !root.current || !tab.visible) return;
      const node = root.current;
      const locate = () => {
        const target = node.querySelector('[data-pdf-page="'+page+'"]');
        if (!target) return;
        target.scrollIntoView({block:'start'});
        if (target.querySelector('canvas')?.closest('[hidden]') === null) observer.disconnect();
      };
      const observer = new MutationObserver(locate);
      observer.observe(node,{childList:true,subtree:true,attributes:true,attributeFilter:['hidden']});
      locate(); return () => observer.disconnect();
    }, [content, page, tab.visible]);
    return h('section',{ref:root,className:'jsd-original'},
      h('div',{className:'jsd-original-toolbar'},h('span',null,'原文 · 第 '+page+' 页'),
        address && h('a',{href:address,download:true},content?.kind === 'image' ? '下载此页图片' : '下载原文件')),
      error ? h('p',{role:'alert'},error) : !content ? h('p',{role:'status'},'读取原文…') :
        content.kind === 'image' ? h('img',{className:'jsd-spectrum-original',src:content.url,alt:'图谱原始页面 · 第 '+page+' 页'}) :
        renderSlot(originalPdfSlot, {resourceAddress:address,content,wrap:false,
          scrollportRef:()=>{},addResource:()=>{},setResources:()=>{}},
          {entryKey:nativePdfKey,hookContext:useTabInfo}));
  }
  function registerSourceMarkdown(scope) {
    const sourceTitles=new Map();
    // Separate seats retain the native child components without redeclaring
    // slots owned by ui-chat. The wrapper only projects assistant text.
    const seats = {
      'conversation.chat.reasoning.body':'enterprise.source.reasoning',
      'conversation.chat.message.images':'enterprise.source.images',
    };
    scope.slots.inject('conversation.chat.node', () => {
      let current, dispose;
      const sync = () => {
        const entry = scope.slots.entries('conversation.chat.node').find(item =>
          item.options.key === 'assistant-step' && (item.options.priority || 0) === 0);
        if (!entry || entry === current) return;
        current = entry; dispose?.();
        function ReadableSources(props) {
          const data=props.node.data;
          const [names,setNames]=React.useState(()=>new Map());
          const {blocks,sources,links}=React.useMemo(()=>sourceLinks.projectSourceBlocks(data.blocks,location.origin,names),[data.blocks,names]);
          const projectEndpoints=[...new Set(links.filter(link=>link.endpoint).map(link=>link.endpoint))].join(',');
          React.useEffect(()=>{
            let active=true;
            for(const endpoint of projectEndpoints.split(',').filter(Boolean)) {
              if(!sourceTitles.has(endpoint))sourceTitles.set(endpoint,fetch(endpoint,{credentials:'same-origin'})
                .then(response=>response.ok ? response.json() : null)
                .then(value=>typeof value?.data?.name==='string' ? value.data.name.replace(/\s+/g,' ').trim() : '')
                .catch(()=>''));
              void sourceTitles.get(endpoint).then(name=>{
                if(active&&name)setNames(previous=>previous.get(endpoint)===name ? previous : new Map([...previous,[endpoint,name]]));
              });
            }
            return ()=>{active=false;};
          },[projectEndpoints]);
          const renderSlot=React.useCallback((name,owner,options)=>
            props.renderSlot(seats[name] || name,owner,options),[props.renderSlot]);
          const native=h(entry.component,{...props,renderSlot,node:{...props.node,data:{...data,blocks}}});
          if(!sources.length&&!links.length) return native;
          const showSourceTitle=event=>{
            const link=event.target.closest?.('a[data-jsd-citation]');
            if(!link)return;
            const item=sources.find(candidate=>candidate.locations.some(place=>place.url===link.href));
            const id=new URL(link.href).pathname.match(/^\/api\/v1\/knowledge\/documents\/([a-f0-9-]{36})\/versions\//)?.[1];
            if(!item||!id)return;
            if(!sourceTitles.has(id)) sourceTitles.set(id,fetch('/api/v1/knowledge/documents/'+id,{credentials:'same-origin'})
              .then(response=>response.ok ? response.json() : null)
              .then(value=>String(value?.data?.title||'').replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'').replace(/[_\s]+/g,' ').trim())
              .catch(()=>''));
            void sourceTitles.get(id).then(title=>{
              if(!title||!link.isConnected)return;
              const page=item.locations.find(place=>place.url===link.href)?.page;
              link.title=title+(page ? ' · 第 '+page+' 页' : '');
              link.setAttribute('aria-label','查看 '+link.textContent.trim()+'：'+link.title);
            });
          };
          return h('div',{className:'jsd-cited-answer',ref:node=>{
            node?.querySelectorAll('a[href]').forEach(link=>{
              // Inline programs and linked images retain their native treatment.
              if(link.closest('code,pre')||link.querySelector('img'))return;
              const item=sources.find(candidate=>candidate.locations.some(place=>place.url===link.href));
              const other=links.find(candidate=>candidate.url===link.href);
              if(!item&&!other) return;
              if(other) {
                link.title=other.title+'\n'+other.url;
                link.setAttribute('aria-label','查看'+(other.kind==='project' ? '项目：' : other.kind==='business' ? '业务资料：' : '网页来源：')+other.title);
                link.setAttribute('data-jsd-citation',other.kind);
                return;
              }
              const place=item.locations.find(candidate=>candidate.url===link.href);
              const title=item.title+(place.page ? ' · 第 '+place.page+' 页' : '');
              link.title=title;link.setAttribute('aria-label','查看 '+link.textContent.trim()+'：'+title);
              link.setAttribute('data-jsd-citation','paper');
            });
          },onPointerOverCapture:showSourceTitle,onFocusCapture:showSourceTitle},native);
        }
        dispose=scope.slots.register({name:'conversation.chat.node',key:'assistant-step',priority:-100,
          children:Object.fromEntries(Object.values(seats).map(name=>[name,{kind:'single',scope:'session'}])),
          inject:entry.inject,locale:entry.locale,store:entry.store},ReadableSources);
      };
      const unsubscribe=scope.slots.subscribe('conversation.chat.node',sync);
      sync();return ()=>{unsubscribe();dispose?.();};
    });
    for (const [native,seat] of Object.entries(seats)) {
      scope.slots.inject(seat, () => {
        let current, dispose;
        const sync=()=>{
          const entry=scope.slots.entriesOfSlot(native)[0];
          if(entry===current)return;
          dispose?.();current=entry;
          dispose=entry && scope.slots.register({name:seat,inject:entry.inject,
            locale:entry.locale,store:entry.store},entry.component);
        };
        const unsubscribe=scope.slots.subscribe(native,sync);
        sync();return ()=>{unsubscribe();dispose?.();};
      });
    }
  }
  function registerOriginals(scope) {
    scope.effect(() => scope.sidebarRightTabs.register({id:'@jsd-aird/original',kind:'enterprise-original',
      patterns:['dsh-resource://enterprise-original/**'],title:()=> '原文'}));
    scope.slots.inject('sidebar.right.pane.tab', () => scope.slots.register({
      name:'sidebar.right.pane.tab',key:'@jsd-aird/original',children:{
        [originalPdfSlot]:{kind:'keyed',scope:'session',inject:{hooks:{tabInfo:(_standard,info)=>info}}},
      },
    },OriginalBody));
    // Each child slot has one owner. Reuse the native registration in our own
    // seat, including its viewing store, locale and tab lifetime injection.
    // Subscribe because the native lazy renderer may register after this entry.
    scope.slots.inject(originalPdfSlot, () => {
      let current, dispose;
      const sync = () => {
        const entry = scope.slots.entriesOfSlot('sidebar.right.tab.document')
          .find(item => item.options.key === nativePdfKey);
        if (entry === current) return;
        dispose?.(); current = entry;
        dispose = entry && scope.slots.register({name:originalPdfSlot,key:nativePdfKey,
          store:entry.store,inject:entry.inject,locale:entry.locale},entry.component);
      };
      const unsubscribe = scope.slots.subscribe('sidebar.right.tab.document',sync);
      sync();
      return () => {unsubscribe();dispose?.();};
    });
    scope.effect(() => {
      const open = event => {
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const link = event.target.closest?.('a[href]');
        if (!link || link.hasAttribute('download')) return;
        const url = originalUrl(link.href);
        if (!url) return;
        event.preventDefault(); event.stopPropagation();
        scope.sidebarRight.openResource('dsh-resource://enterprise-original/'+encode(url.href));
      };
      document.addEventListener('click',open,true);
      return () => document.removeEventListener('click',open,true);
    });
  }
  function ResourceBody({ useTabInfo }) {
    const {tab} = useTabInfo();
    const token = tab.navigation.address.split('/').at(-1);
    const [value, setValue] = useState(null), [error, setError] = useState(''), [offset, setOffset] = useState(0);
    useEffect(() => {setOffset(0);}, [token]);
    useEffect(() => {
      const controller = new AbortController(); setValue(null); setError('');
      api('resource', {ref:refOnly(decode(token)),offset}, controller.signal).then(setValue)
        .catch(e => {if (!controller.signal.aborted) setError(e.message);});
      return () => controller.abort();
    }, [token, offset]);
    if (error) return h('p', {role:'alert',className:'jsd-resource'}, error);
    if (!value) return h('p',{className:'jsd-resource',role:'status'},'读取资料…');
    const ref = value.ref;
    if (ref.kind === 'SPECTRUM') return h(spectrumUI.SpectrumResourceBody, {React,value});
    const edit = ref.kind === 'PROJECT' ? '/projects/' + ref.resource_id : ref.kind === 'EXPERIMENT' ? '/experiments/' + ref.resource_id : null;
    return h('article',{className:'jsd-resource'},h('h2',null,value.title),h('p',null,'固定版本'),
      edit && h('a',{href:edit,target:'_blank',rel:'noopener'},'打开业务页面'),
      (value.candidates || []).map((item,index) => h('section',{key:index},
        h('small',null,item.source?.anchor?.page_no ? '第 ' + item.source.anchor.page_no + ' 页' : ''),
        h('pre',null,item.text))),
      offset > 0 && button('上一段',()=>setOffset(Math.max(0,offset-8))),
      value.next_offset != null && button('下一段',()=>setOffset(value.next_offset)));
  }
  function registerInputs(scope) {
    const open = value => scope.sidebarRight.openResource(addressFor(value));
    scope.sidebarRightTabs.register({id:'@jsd-aird/enterprise-resource',kind:'enterprise-resource',
      patterns:['dsh-resource://enterprise/**'],title:()=> '资料原文'});
    scope.slots.inject('sidebar.right.pane.tab', () => scope.slots.register({
      name:'sidebar.right.pane.tab',key:'@jsd-aird/enterprise-resource',
    },ResourceBody));
    const {ReferenceChip} = require('@deepseek-ai/dsh-client-ui-primitives');
    scope.uiConversation.userText.register({id:'enterprise-resource',order:0,match(text,from) {
      const expression = /\[([^\]\n]{1,240})\]\(jsd-resource:([A-Za-z0-9_-]{1,2048})\)/g; expression.lastIndex=from;
      const match = expression.exec(text);
      return match ? {id:'enterprise-resource',start:match.index,end:match.index+match[0].length,data:{label:match[1],token:match[2]}} : undefined;
    }});
    scope.slots.inject('conversation.user.text.node',()=>scope.slots.register({
      name:'conversation.user.text.node',key:'enterprise-resource',priority:100,
    },({node,interactive})=>h(ReferenceChip,{appearance:'file',label:node.data.label,title:node.data.label,
      onActivate:interactive === false ? undefined : ()=>open(decode(node.data.token))})));
    scope.effect(()=>scope.inputTriggers.registerSource({
      trigger:'@',name:'企业资料',order:-10,
      async candidates({sessionId},{query,signal}) {
        const result = await api('context',{session_id:sessionId,query},signal);
        return (result.choices || []).flatMap(item => {
          const row = {name:item.title,label:item.title,section:item.section,description:'引用固定版本',icon:'file',
            value:JSON.stringify({ref:item.ref,label:item.title})};
          return item.ref.kind === 'KB_PUBLICATION' ? [row,{...row,name:'只检索：'+item.title,
            label:'只检索：'+item.title,description:'仅限制本次请求',section:'仅本次检索',
            value:JSON.stringify({ref:{...item.ref,only:true},label:'只检索：'+item.title})}] : [row];
        });
      },
      onPick({candidate}) {
        const value = JSON.parse(candidate.value);
        return {insert:{source:'企业资料',ref:candidate.value,label:value.label,appearance:'file',
          clipboardText:marker(value.label,value.ref)}};
      },
      codec:{
        clipboardText(value) {const item=JSON.parse(value);return marker(item.label,item.ref);},
        async serialize(value,signal) {
          const item=JSON.parse(value), verified=await api('resource',{ref:refOnly(item.ref)},signal);
          return marker(item.ref.only ? '只检索：'+verified.title : verified.title,{...verified.ref,...(item.ref.only ? {only:true} : {})});
        },
      },
      openReference(_session,{ref}) {open(JSON.parse(ref).ref);return true;},
    }));
  }
  function registerBusinessToolViews(scope) {
    const {DisclosureRow,IconSearchOutlineRegular} = require('@deepseek-ai/dsh-client-ui-primitives');
    const titles = {memory_search:'查找个人记忆',memory_manage:'管理个人记忆',tool_search:'查找企业能力',retrieve_context:'检索研发资料',knowledge_search:'检索文献',
      document_outline:'查看文献目录',document_read:'阅读文献',table_read:'读取表格',evidence_read:'核对原文',
      private_document_outline:'查看附件目录',private_document_read:'阅读附件',private_document_search:'检索附件',
      bibliography_lookup:'查询书目信息',literature_search:'检索论文',literature_expand:'查找相关论文',
      literature_acquire:'获取原文',literature_acquire_status:'检查原文状态',
      search_projects:'查找项目',get_project_snapshot:'读取项目',list_project_stages:'读取项目阶段',
      list_project_tasks:'读取项目任务',get_task_detail:'读取任务详情',search_experiments:'查找实验',
      get_experiment_record:'读取实验版本',create_experiment_draft:'创建实验草稿',
      get_compute_context:'读取计算上下文',run_model_training:'建立模型',activate_trained_model:'启用模型',
      get_compute_result:'读取计算结果',get_compute_receipt:'查询操作回执',
      run_prediction:'预测配方表现',run_formula_design:'推荐配方',run_experiment_optimization:'推荐实验方案',
      save_experiment_measurements:'保存实测草稿',get_experiment_loop:'读取实验进展',
      continue_experiment_optimization:'继续优化',get_research_briefing_context:'读取项目进展',
      report_check:'核查正式报告',report_save:'保存正式报告',artifact_save:'登记成果',
      evidence_score:'筛选证据',evidence_save:'保存证据',matrix_record:'整理研究依据',note_save:'保存研究笔记'};
    function BusinessTool({block,toolName,phase,useDisclosure}) {
      const {expanded,toggle} = useDisclosure();
      const [sourceError,setSourceError]=useState('');
      const meta = block.meta?.enterprise ? block.meta : {};
      let value;
      try {value = JSON.parse(block.content?.filter(part=>part.type==='text').map(part=>part.text).join('') || '{}');}
      catch {value = {};}
      const rows = value.candidates || value.hits || value.entries || [];
      const businessText = text => String(text || '').replace(/测试实验|验收|测试|模拟|验证/g,
        word => ({验收:'完成检查',测试:'测定',模拟:'研发参考',验证:'评估'})[word] || word);
      const query = businessText(block.args?.text('query'));
      const failed = block.isError || meta.failed || Boolean(value.error);
      const summary = failed ? '操作未完成' : phase !== 'result' ? query || '处理中' :
        toolName==='memory_manage' ? value.summary || '结果待核对' : toolName==='memory_search' ? rows.length+' 条记忆' :
        meta.count !== undefined ? meta.count+' 条结果' : businessText(meta.summary) || '已完成';
      const content = rows.slice(0,8).map((row,index)=>h('section',{key:index},
        h('strong',null,row.title || row.name || row.source?.title || '结果 '+(index+1)),
        h('p',{style:{whiteSpace:'pre-wrap'}},String(row.text || row.snippet || row.quote?.text || '').slice(0,1800)),
        toolName==='memory_search' && row.source?.session_id && button('查看原始会话'+(row.source.turn?' · 第 '+row.source.turn+' 轮':''),async()=>{
          try {
            const {source}=await api('memory',{action:'source',entry_id:row.id,content_revision:row.content_revision});
            await scope.uiWorkspace.openSession(source.session_id);
          } catch(error){setSourceError(error.message);}
        })));
      const errorText = businessText(meta.summary || value.error || '业务请求未完成，请稍后继续。')
        .replace(/^[A-Z][A-Z_0-9]+:\s*/, '');
      return h(DisclosureRow,{className:'jsd-business-tool',contentClassName:'jsd-tool-header',
        contentLayoutClassName:'jsd-tool-header-content',
        title:titles[toolName] || '企业查询',icon:h(IconSearchOutlineRegular,{size:14}),
        open:expanded,expandable:phase==='result',onToggle:toggle,expandOnRowClick:true,
        running:phase!=='result',collapsedContent:h('span',{className:'jsd-tool-summary',title:summary},' · '+summary)},
        h('div',{className:'jsd-tool-content'},
          failed ? h('p',{role:'status'},errorText) : content.length ? content : h('p',null,
            toolName.startsWith('memory_') ? value.summary || meta.summary || '业务结果已返回' : businessText(value.summary || meta.summary) || '业务结果已返回'),
          sourceError && h('p',{role:'alert'},sourceError),
          (meta.sources || []).map((source,index)=>h('p',{key:'source-'+index},h('a',{href:source.url,className:'jsd-source-link',title:source.label},
            sourceLinks.sourceLinkLabel(source.label,new URL(source.url,location.origin)))))));
    }
    scope.slots.inject('tool.call.toolview',function* () {
      for (const key of Object.keys(titles)) yield scope.slots.register({name:'tool.call.toolview',key},BusinessTool);
    });
  }
  function latestTurnBoundary(window) {
    return window.entries.findLast(row=>row.type==='event' &&
      (row.event.type==='turn/start' || row.event.type==='turn/end'))?.event;
  }
  function continuationLabel(boundary) {
    if (boundary?.type!=='turn/end') return null;
    const reason=boundary.data.reason;
    if (reason.kind==='interrupted') return '任务已中断';
    if (reason.kind==='max-tokens') return '回答已截断';
    if (reason.kind==='aborted' && ['user','disposed','legacy'].includes(reason.reason.kind)) return '任务已暂停';
    return null;
  }
  function idleSession(session) {
    return session.openState==='open' && !session.running && !session.removed && !session.blank && session.subagent===null;
  }
  function registerDeletedSessionNavigation(scope) {
    function DeletedSessionNavigation({useSession,next}) {
      const removed=useSession(value=>value.removed), handled=useRef(false);
      useEffect(()=>{
        if (!removed) handled.current=false;
        else if (!handled.current) {handled.current=true;next();}
      },[removed,next]);
      return null;
    }
    scope.slots.inject('conversation.input.right',()=>scope.slots.register({
      name:'conversation.input.right',id:'enterprise-deleted-session-navigation',
      inject:()=>({next:()=>scope.uiWorkspace.startSession()}),
    },DeletedSessionNavigation));
  }
  function registerPluginMenuPolicy(scope) {
    scope.effect(()=>{
      let observer;
      // The pinned addon's legacy menu has public data markers; its newer
      // native menu omits them. Limit this presentation policy to an open
      // workspace menu. The corresponding server operations stay denied.
      const hideDesktopAction=()=>{
        for (const item of document.querySelectorAll('[role="menuitem"]')) {
          if (['打开目录','Open folder','Открыть папку'].includes(item.textContent.trim()))
            (item.parentElement?.childElementCount===1 ? item.parentElement : item).style.display='none';
        }
      };
      const update=()=>{
        observer?.disconnect();observer=undefined;
        if (scope.contextMenus.active.getSnapshot()?.kind!=='workspace') return;
        hideDesktopAction();observer=new MutationObserver(hideDesktopAction);
        observer.observe(document.body,{childList:true,subtree:true});
      };
      const unsubscribe=scope.contextMenus.active.subscribe(update);update();
      return ()=>{unsubscribe();observer?.disconnect();};
    });
  }
  function registerContinuation(scope) {
    const pending=new Map();
    const {IconTriangleRightFillRegular}=require('@deepseek-ai/dsh-client-ui-primitives');
    function Continuation({useSession,useInput,useEvents,useComposerBlock,useAccess,resume}) {
      const session=useSession(value=>value), input=useInput(value=>value);
      const boundary=useEvents(latestTurnBoundary), block=useComposerBlock(value=>value);
      const allowed=useAccess('session/prompt');
      const [busy,setBusy]=useState(false), [accepted,setAccepted]=useState(null);
      const label=continuationLabel(boundary);
      if (!label || !idleSession(session) || allowed===false || input.draft.trim()!=='' || input.attachmentIds.length>0) return null;
      const sending=busy || accepted===boundary.seq;
      const disabled=sending || input.phase!=='plain' || block!==undefined || session.pendingSubmissions.length>0;
      async function act() {
        if (disabled) return;
        setBusy(true);
        try {if (await resume(boundary.seq)) setAccepted(boundary.seq);}
        finally {setBusy(false);}
      }
      return button(sending ? h('svg',{className:'jsd-continue-spinner',viewBox:'0 0 16 16',width:16,height:16,'aria-hidden':true},
        h('circle',{cx:8,cy:8,r:6,fill:'none',stroke:'currentColor',strokeOpacity:.35,strokeWidth:2}),
        h('path',{d:'M8 2a6 6 0 0 1 6 6',fill:'none',stroke:'currentColor',strokeWidth:2,strokeLinecap:'round'})) :
        h(IconTriangleRightFillRegular,{size:16,'aria-hidden':true}),act,
        {className:'jsd-continue-action',disabled,'aria-label':'继续任务','aria-busy':sending,
          title:sending ? '继续中…' : '继续任务',onMouseDown:event=>event.preventDefault()});
    }
    scope.slots.inject('conversation.input.right',()=>scope.slots.register({
      name:'conversation.input.right',id:'enterprise-continuation',
      inject:sessionId=>{
        const binding=scope.sessions.binding(sessionId), input=scope.conversation.input.for(scope.sessions.scope(sessionId));
        const block=scope.conversation.blocks.storeFor(sessionId);
        return {hooks:{events:binding.eventSource,composerBlock:block},resume:async seq=>{
          const current=scope.sessions.binding(sessionId);
          if (current!==binding) return false;
          const boundary=latestTurnBoundary(binding.eventSource.getSnapshot());
          const session=binding.session.getSnapshot();
          const draft=input.state.getSnapshot();
          if (boundary?.seq!==seq || !continuationLabel(boundary) || !idleSession(session)
            || session.pendingSubmissions.length>0 || draft.phase!=='plain' || draft.draft.trim()!=='' || draft.attachmentIds.length>0
            || block.getSnapshot()!==undefined) return false;
          const requestId='enterprise-continue-'+sessionId+'-'+seq;
          if (pending.has(requestId)) return pending.get(requestId);
          // One native request per stopped boundary, also across remounts and lost responses.
          // Sending directly leaves the editor's draft, references and attachments intact.
          const operation=(async()=>{
            try {
              const result=await binding.session.prompt([{type:'text',text:'继续'}],'queue',undefined,requestId);
              return result.ok;
            } catch {
              input.notify('error','继续请求尚未确认，请检查连接后重试。');
              return false;
            }
          })();
          pending.set(requestId,operation);
          try {return await operation;}
          finally {pending.delete(requestId);}
        }};
      },
    },Continuation));
  }
  function registerAdmissionLabel(scope) {
    scope.effect(() => scope.resources.register({
      protocol:'enterprise-run-state',
      async *open(address,{signal}) {
        const sessionId=decodeURIComponent(new URL(address).pathname.slice(1));
        while(!signal.aborted) {
          try {
            const value=await api('run-state',{session_id:sessionId},signal);
            if(signal.aborted)return;
            yield {ok:true,value};
          } catch {
            if(signal.aborted)return;
            yield {ok:false,error:{code:'RUN_STATE_UNAVAILABLE',message:'正在重新连接，原请求已保留'}};
          }
          await new Promise(resolve=>{
            const done=()=>{clearTimeout(timer);signal.removeEventListener('abort',done);resolve();};
            const timer=setTimeout(done,1500);
            signal.addEventListener('abort',done,{once:true});
            if(signal.aborted)done();
          });
        }
      },
    }));
    function AdmissionLabel({sessionId,label,useResource}) {
      const state=useResource('dsh-resource://enterprise-run-state/'+encodeURIComponent(sessionId));
      const run=state.value?.run;
      const text=run?.status==='QUEUED'
        ? run.admission==='GRANTED' ? '正在准备任务…' : '当前任务较多，正在排队'
        : label;
      return h('span',{...(run?.status==='QUEUED' ? {role:'status','aria-live':'polite'}:{})},text);
    }
    scope.slots.inject('conversation.chat.running-label',()=>scope.slots.register({
      name:'conversation.chat.running-label',priority:-100,
    },AdmissionLabel));
  }
  return { inject: ['slots','theme'], apply(ctx) {
    ctx.effect(() => ctx.theme.overrideTokens('@jsd-aird/enterprise-plugin',enterpriseTheme.tokens));
    ctx.effect(() => {
      // The workbench is a separate document; it must load the same self-hosted
      // font as the business application rather than relying on a previous visit.
      const fonts = document.createElement('link');
      fonts.rel = 'stylesheet'; fonts.href = base + 'font-assets/index.css';
      fonts.dataset.jsdFonts = '';
      const style = document.createElement('style');
      style.dataset.jsdTheme = ''; style.textContent = enterpriseTheme.css;
      const previous = document.body.getAttribute('data-jsd-workbench');
      document.body.setAttribute('data-jsd-workbench',''); document.head.append(fonts, style);
      return () => {
        fonts.remove(); style.remove();
        if (previous === null) document.body.removeAttribute('data-jsd-workbench');
        else document.body.setAttribute('data-jsd-workbench',previous);
      };
    });
    ctx.slots.inject('sidebar.brand.mark',()=>ctx.slots.register({name:'sidebar.brand.mark'},BrandMark));
    ctx.slots.inject('sidebar.brand.name',()=>ctx.slots.register({name:'sidebar.brand.name'},()=>h('span',{className:'jsd-brand-name'},'AI工作台')));
    ctx.slots.inject('conversation.hero.brand.mark',()=>ctx.slots.register({name:'conversation.hero.brand.mark'},HeroMark));
    ctx.inject(['uiWorkspace'],registerBriefingNavigation);
    ctx.inject(['uiWorkspace'],scope=>{
      const id=new URL(location.href).searchParams.get('conversion');
      if(id && /^[a-f0-9-]{36}$/.test(id)) scope.uiWorkspace.openSession('session-conversion-'+id);
    });
    ctx.slots.inject('sidebar.footer.action',()=>ctx.slots.register({name:'sidebar.footer.action',id:'enterprise-navigation'},Navigation));
    ctx.inject(['inputTriggers','uiConversation','sidebarRight','sidebarRightTabs'],registerInputs);
    ctx.inject(['sidebarRight','sidebarRightTabs'],registerOriginals);
    ctx.inject(['resources'],registerAdmissionLabel);
    ctx.inject(['conversation','sessions'],registerContinuation);
    ctx.inject(['uiWorkspace'],registerDeletedSessionNavigation);
    ctx.inject(['uiWorkspace','contextMenus'],scope=>{
      const {Switch,Modal,Toast}=require('@deepseek-ai/dsh-client-ui-primitives');
      memoryUI.registerMemoryUI(scope,{React,api,Switch,Modal,Toast});
    });
    ctx.inject(['contextMenus'],registerPluginMenuPolicy);
    ctx.inject(['uiWorkspace','conversation','sessions','layout'],scope=>{
      const {Modal,Menu,MenuItemButton}=require('@deepseek-ai/dsh-client-ui-primitives');
      const expertActions=expertsUI.registerExpertsUI(scope,{React,api,Modal,Menu,MenuItemButton,tasks});
      const {useExpert}=expertActions;
      spectrumUI.registerSpectrumUI(scope,{React,api,Modal,useExpert,marker});
      registerWelcomeCards(scope,expertActions);
    });
    registerSourceMarkdown(ctx);
    ctx.inject(['uiWorkspace'],registerBusinessToolViews);
  }};
}});
