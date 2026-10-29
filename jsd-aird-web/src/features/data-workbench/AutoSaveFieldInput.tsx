import { LoadingOutlined } from '@ant-design/icons';
import { Input } from 'antd';
import { useEffect, useRef } from 'react';

const SAVE_DELAY_MS = 900;

/** One field edit control for every data workbench: debounce, blur and Enter all save. */
export function AutoSaveFieldInput({
  label,
  value,
  savedValue,
  disabled,
  saving,
  placeholder = '核对原文后输入实际值',
  onChange,
  onSave,
}: {
  label: string;
  value: string;
  savedValue: string;
  disabled?: boolean;
  saving?: boolean;
  placeholder?: string;
  onChange: (value: string) => void;
  onSave: (value: string) => void;
}) {
  const timer = useRef<number>();
  const latest = useRef(value);
  latest.current = value;
  const save = (next: string) => {
    window.clearTimeout(timer.current);
    timer.current = undefined;
    if (next !== savedValue && !saving) onSave(next);
  };
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <Input
      aria-label={`修正${label}`}
      disabled={disabled || saving}
      placeholder={placeholder}
      value={value}
      suffix={(
        <span className="data-field-autosave-indicator" aria-live="polite">
          {saving ? <LoadingOutlined title="正在自动保存" /> : null}
        </span>
      )}
      onChange={(event) => {
        const next = event.target.value;
        latest.current = next;
        onChange(next);
        window.clearTimeout(timer.current);
        if (next !== savedValue) timer.current = window.setTimeout(() => save(next), SAVE_DELAY_MS);
      }}
      onBlur={() => save(latest.current)}
      onPressEnter={(event) => event.currentTarget.blur()}
    />
  );
}
