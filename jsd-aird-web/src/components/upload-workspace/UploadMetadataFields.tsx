import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';
import { UploadOptionalFields } from './UploadOptionalFields';
import { useDesktopLayout } from '@/hooks/use-desktop-layout';

/** Keep required classification and visibility ahead of optional metadata on desktop. */
export function UploadMetadataFields({ children, primaryLabels, summary }: {
  children: ReactElement<{ children?: ReactNode }>;
  primaryLabels: string[];
  summary: string;
}) {
  const desktop = useDesktopLayout();
  if (!desktop) return children;
  const fields = Children.toArray(children.props.children).filter(isValidElement) as ReactElement<{ label?: string; required?: boolean; children?: ReactNode }>[];
  const primary = fields.filter(field => field.props.required || primaryLabels.includes(field.props.label || ''));
  const optional = fields.filter(field => !primary.includes(field));
  const filledCount = optional.filter(field => Children.toArray(field.props.children).some(child => {
    if (!isValidElement<{ value?: unknown }>(child)) return false;
    const value = child.props.value;
    return Array.isArray(value) ? value.length > 0 : value !== undefined && value !== null && value !== '';
  })).length;
  return cloneElement(children, {}, <>
    <div className="upload-workspace-required-fields">{primary}</div>
    {optional.length > 0 && <UploadOptionalFields summary={`${summary}${filledCount ? ` · 已填写 ${filledCount} 项` : ''}`}>{optional}</UploadOptionalFields>}
  </>);
}
