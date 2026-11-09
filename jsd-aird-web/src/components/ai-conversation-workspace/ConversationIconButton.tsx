import { Button, Tooltip, type ButtonProps } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { cleanConversationText } from './conversation-text';

type IconName =
  | 'copy'
  | 'attach'
  | 'sources'
  | 'files'
  | 'search'
  | 'send'
  | 'stop'
  | 'edit'
  | 'close'
  | 'more'
  | 'grip';

export function ConversationIcon({ name }: { name: IconName }) {
  return (
    <svg
      className="conversation-icon"
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {name === 'copy' && (
        <>
          <rect x="8" y="8" width="12" height="13" rx="2.5" />
          <path d="M16 8V5.5A2.5 2.5 0 0 0 13.5 3h-8A2.5 2.5 0 0 0 3 5.5v8A2.5 2.5 0 0 0 5.5 16H8" />
        </>
      )}
      {name === 'attach' && (
        <path d="m9 13 6.8-6.8a3 3 0 0 1 4.2 4.2L10.4 20a5 5 0 0 1-7.1-7.1l9.5-9.5a2 2 0 0 1 2.9 2.8L6.2 15.7a1 1 0 0 0 1.4 1.4L17 7.7" />
      )}
      {name === 'sources' && (
        <>
          <path d="M3 6.5A2.5 2.5 0 0 1 5.5 4h4l2 2H19a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
          <path d="M8 12h8m-8 4h5" />
        </>
      )}
      {name === 'files' && (
        <>
          <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z" />
          <path d="M14 3v5h5M9 13h6m-6 4h4" />
        </>
      )}
      {name === 'search' && (
        <>
          <circle cx="12" cy="12" r="9" />
          <ellipse cx="12" cy="12" rx="4" ry="9" />
          <path d="M3 12h18" />
        </>
      )}
      {name === 'send' && <path d="M12 19V5m-6 6 6-6 6 6" />}
      {name === 'close' && <path d="m6 6 12 12M6 18 18 6" />}
      {name === 'more' && (
        <g fill="currentColor" stroke="none">
          <circle cx="5" cy="12" r="1.5" />
          <circle cx="12" cy="12" r="1.5" />
          <circle cx="19" cy="12" r="1.5" />
        </g>
      )}
      {name === 'grip' && (
        <g fill="currentColor" stroke="none">
          {[7, 12, 17].map((y) => (
            <g key={y}>
              <circle cx="9" cy={y} r="1.2" />
              <circle cx="15" cy={y} r="1.2" />
            </g>
          ))}
        </g>
      )}
      {name === 'edit' && (
        <>
          <path d="m15 4 5 5M4 20l5-1L20 8a3.5 3.5 0 0 0-5-5L4 14Z" />
        </>
      )}
      {name === 'stop' && (
        <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" />
      )}
    </svg>
  );
}

export function ConversationIconButton({
  label,
  name,
  tooltip = label,
  className = '',
  ...props
}: Omit<ButtonProps, 'icon' | 'children'> & {
  label: string;
  name: IconName;
  tooltip?: string | false;
}) {
  const button = (
    <Button
      type="text"
      {...props}
      className={`conversation-icon-button ${className}`}
      aria-label={label}
      icon={<ConversationIcon name={name} />}
    />
  );
  return tooltip === false ? (
    button
  ) : (
    <Tooltip title={tooltip} trigger={['hover', 'focus']}>
      {button}
    </Tooltip>
  );
}

export function CopyAnswerButton({ text }: { text: string }) {
  const [notice, setNotice] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <div className="conversation-answer-actions">
      <ConversationIconButton
        label="复制回答"
        name="copy"
        tooltip={notice || '复制回答'}
        onClick={() => {
          void navigator.clipboard
            .writeText(cleanConversationText(text))
            .then(() => {
              setNotice('已复制');
              clearTimeout(timer.current);
              timer.current = setTimeout(() => setNotice(''), 2000);
            })
            .catch(() => setNotice('暂时无法复制，请选中文字复制'));
        }}
      />
      <span role="status" className="conversation-copy-notice">
        {notice}
      </span>
    </div>
  );
}
