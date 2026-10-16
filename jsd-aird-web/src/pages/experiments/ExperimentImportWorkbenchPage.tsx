import { useCallback, useState } from 'react';
import { useParams } from 'react-router-dom';

import { DataImportJobPage } from '@/pages/data/DataImportJobPage';
import type { ExperimentAssemblyLink } from '@/services/data/data-api';
import { ExperimentWorkspacePage } from './ExperimentWorkspacePage';

interface SelectionState {
  taskId: string;
  experimentId?: string;
  linkedExperimentCount: number;
}

/** Keeps import review and the resulting experiment in one route/workbench flow. */
export function ExperimentImportWorkbenchPage() {
  const { id = '' } = useParams();
  const [selection, setSelection] = useState<SelectionState>({ taskId: id, linkedExperimentCount: 0 });
  const current = selection.taskId === id
    ? selection
    : { taskId: id, linkedExperimentCount: 0 };

  const openExperiment = useCallback((experimentId: string) => {
    setSelection((previous) => ({
      taskId: id,
      linkedExperimentCount: previous.taskId === id ? previous.linkedExperimentCount : 0,
      experimentId,
    }));
  }, [id]);

  const updateLinkedExperiments = useCallback((links: ExperimentAssemblyLink[]) => {
    const linkedExperimentCount = links.filter((link) => Boolean(link.experimentId)).length;
    setSelection((previous) => ({
      taskId: id,
      linkedExperimentCount,
      experimentId: previous.taskId === id ? previous.experimentId : undefined,
    }));
  }, [id]);

  const returnToImport = useCallback(() => {
    setSelection((previous) => ({
      taskId: id,
      linkedExperimentCount: previous.taskId === id ? previous.linkedExperimentCount : 0,
    }));
  }, [id]);

  if (current.experimentId) {
    return <ExperimentWorkspacePage
      key={current.experimentId}
      experimentId={current.experimentId}
      returnTo="/experiments/upload"
      onBack={current.linkedExperimentCount > 1 ? returnToImport : undefined}
    />;
  }

  return <DataImportJobPage
    key={id}
    workspaceMode="EXPERIMENT"
    onExperimentReady={openExperiment}
    onExperimentLinksReady={updateLinkedExperiments}
  />;
}
