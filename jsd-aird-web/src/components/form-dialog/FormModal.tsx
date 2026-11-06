import { App, Form, Modal, type FormInstance, type ModalProps } from 'antd';
import { useEffect, useRef, useState, type MouseEvent } from 'react';

interface FormModalProps extends Omit<ModalProps, 'onOk'> {
  onOk?: (event: MouseEvent<HTMLButtonElement>) => void | Promise<unknown>;
  form?: FormInstance;
  /** For controlled fields outside an Ant Design Form. */
  dirty?: boolean;
  /** Controlled draft values (e.g. a relation picker or a rename field). */
  draft?: unknown;
  /** Reset dirty tracking after a successful save that keeps the dialog open. */
  resetKey?: string | number;
}

function focusFirstFormError(form?: FormInstance) {
  const first = form?.getFieldsError().find(field => field.errors.length > 0);
  if (first) form?.scrollToField(first.name, { block: 'center', behavior: 'smooth', focus: true });
}

/** Keeps Ant Design's dialog, focus restoration and existing submit workflow. */
export function FormModal({ form, dirty, draft, resetKey, onCancel, onOk, afterOpenChange, confirmLoading,
  open, className = '', cancelButtonProps, children, ...props }: FormModalProps) {
  const { modal, message } = App.useApp();
  const changed = useRef(false);
  const draftValue = useRef(draft);
  draftValue.current = draft;
  const initialDraft = useRef<string>();
  const busy = useRef(false);
  const confirmingClose = useRef(false);
  const [submitting, setSubmitting] = useState(false);
  const pending = Boolean(confirmLoading || submitting);
  useEffect(() => {
    changed.current = false;
    initialDraft.current = JSON.stringify(draftValue.current);
  }, [open, resetKey]);

  const cancel: NonNullable<ModalProps['onCancel']> = event => {
    if (pending || busy.current || confirmingClose.current) return;
    if (!(dirty ?? (changed.current || initialDraft.current !== JSON.stringify(draft)))) { onCancel?.(event); return; }
    confirmingClose.current = true;
    modal.confirm({ title: '放弃未保存的修改？', content: '关闭后，本次填写的内容将不会保存。',
      okText: '放弃修改', cancelText: '继续编辑', okButtonProps: { danger: true },
      onOk: () => onCancel?.(event), afterClose: () => { confirmingClose.current = false; },
    });
  };

  const submit = async (event: MouseEvent<HTMLButtonElement>) => {
    if (pending || busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      if (form) await form.validateFields();
      await onOk?.(event);
    } catch (reason) {
      // Validation is presented beside the fields. Business failures remain in
      // the caller, which retains its input and existing error messages.
      if (!(reason && typeof reason === 'object' && 'errorFields' in reason)) {
        void message.error(reason instanceof Error ? reason.message : '提交失败，请稍后重试');
      }
    } finally {
      focusFirstFormError(form);
      busy.current = false;
      setSubmitting(false);
    }
  };

  return <Modal {...props} open={open} className={`app-form-dialog ${className}`}
    confirmLoading={pending} onCancel={cancel} onOk={(event) => { void submit(event); }}
    cancelButtonProps={{ ...cancelButtonProps, disabled: pending || cancelButtonProps?.disabled }}
    afterOpenChange={afterOpenChange}>
    <Form.Provider onFormChange={(_, { changedFields }) => {
      // Unlike setFieldsValue, user changes trigger Form.Provider. Async initial
      // values and dependent-field hydration therefore do not mark a draft dirty.
      if (changedFields.some(field => field.touched)) changed.current = true;
    }}>{children}</Form.Provider>
  </Modal>;
}
