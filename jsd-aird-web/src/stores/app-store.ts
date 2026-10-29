import { create } from 'zustand';

interface AppState {
  sidebarCollapsed: boolean;
  setSidebarCollapsed: (collapsed: boolean) => void;
  fieldPanelCollapsed: boolean;
  setFieldPanelCollapsed: (collapsed: boolean) => void;
}

export const useAppStore = create<AppState>((set) => ({
  sidebarCollapsed: false,
  setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
  fieldPanelCollapsed: false,
  setFieldPanelCollapsed: (fieldPanelCollapsed) => set({ fieldPanelCollapsed }),
}));
