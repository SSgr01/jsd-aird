import manifest from '../../../../docs/operation-assistant/pages.json';
import { canViewPathWithMenus, hasPermission } from '@/routes/route-permissions';
import type { AuthUser } from '@/services/auth/auth-api';

export const operationManual = manifest;
export type OperationPage = (typeof manifest.pages)[number];

export function canUseOperationPage(page: OperationPage, user: AuthUser | null) {
  if (!user) return false;
  const pathname = page.path.split('?')[0] || page.path;
  return (
    (page.permissions.length === 0 ||
      page.permissions.some((code) => hasPermission(code, user.permissions))) &&
    canViewPathWithMenus(pathname, user.permissions, user.menuKeys || [])
  );
}

export function currentOperationPage(pathname: string, search = '') {
  return (
    manifest.pages.find((page) => page.path === `${pathname}${search}`) ||
    manifest.pages.find((page) => page.path === pathname) ||
    manifest.pages.find((page) =>
      page.contextPatterns.some((pattern) => new RegExp(pattern).test(pathname)),
    )
  );
}

export function operationSuggestions(pageId: string | undefined, user: AuthUser | null) {
  if (!user) return [];
  const allowed = manifest.sections.filter(
    (section) =>
      section.requiredPermissions.every((code) => hasPermission(code, user.permissions)) &&
      section.pageIds.some((id) => {
        const page = manifest.pages.find((candidate) => candidate.id === id);
        return page && canUseOperationPage(page, user);
      }),
  );
  const current = allowed.filter((section) => section.pageIds.some((id) => id === pageId));
  const contextPage = manifest.pages.find((page) => page.id === pageId);
  const related = contextPage
    ? allowed.filter(
        (section) =>
          !current.includes(section) &&
          section.pageIds.some((id) =>
            manifest.pages.some(
              (page) => page.id === id && page.resourceType === contextPage.resourceType,
            ),
          ),
      )
    : [];
  const other = allowed.filter(
    (section) =>
      !current.includes(section) && !related.includes(section) && section.id !== 'navigation',
  );
  return [...current, ...related, ...other].flatMap((section) => section.questions).slice(0, 3);
}

export function operationSuggestionLabel(question: string) {
  return question
    .replace(/^(?:怎样|如何)\s*/, '')
    .replace(/^(?:从哪里|在哪里)\s*/, '')
    .replace(/(?:在哪里|在什么地方)[？?]?$/, '')
    .replace(/[？?。]+$/, '');
}

/** Generated Markdown can never supply a clickable navigation target. */
export function operationAnswerText(text: string) {
  return text
    .replace(/\[\[\s*(?:H\d+|NO_MATCH|CLARIFY)\s*\]\]/g, '')
    .replace(/\[\[[^\]\n]*$/, '')
    .replace(/!?\[([^\]\n]*)\]\([^\n)]*\)/g, '$1');
}
