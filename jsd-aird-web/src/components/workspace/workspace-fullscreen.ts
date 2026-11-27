import { createContext } from 'react';

/** Lets embedded editors use the nearest work area's fullscreen action. */
export const WorkspaceFullscreenContext = createContext<(() => void) | null>(null);
