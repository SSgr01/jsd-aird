/** The same product mark is served by the standalone AI workbench. */
export function BrandMark({ size = 32 }: { size?: number }) {
  return <img className="app-brand-mark" src="/favicon.svg" width={size} height={size} alt="" draggable={false} />;
}
