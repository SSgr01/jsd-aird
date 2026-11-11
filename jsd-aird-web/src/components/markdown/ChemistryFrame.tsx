import { useEffect, useRef, useState } from 'react';

export function ChemistryFrame({ src, title }: { src: string; title: string }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = useState(390);
  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (event.origin !== location.origin || event.source !== frame.current?.contentWindow) return;
      const value: unknown = event.data;
      if (typeof value !== 'object' || value === null || !('type' in value) || value.type !== 'jsd-chemistry-height'
        || !('height' in value) || typeof value.height !== 'number' || !Number.isFinite(value.height)) return;
      setHeight(Math.max(100, Math.min(8000, value.height + 4)));
    };
    window.addEventListener('message', receive);
    return () => window.removeEventListener('message', receive);
  }, [src]);
  return <iframe ref={frame} title={title} loading="lazy" sandbox="allow-scripts allow-same-origin allow-downloads"
    referrerPolicy="no-referrer" src={src} style={{ width: '100%', height, border: 0, display: 'block' }} />;
}
