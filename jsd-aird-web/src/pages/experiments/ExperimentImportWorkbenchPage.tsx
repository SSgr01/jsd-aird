import { Spin } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

import { DataImportJobPage } from '@/pages/data/DataImportJobPage';
import { dataApi, type ExperimentAssemblyLink } from '@/services/data/data-api';
import { ExperimentWorkspacePage } from './ExperimentWorkspacePage';

interface SelectionState {
  taskId: string;
  experimentId?: string;
  linkedExperimentCount: number;
  resolved: boolean;
}

/** Keeps import review and the resulting experiment in one route/workbench flow. */
export function ExperimentImportWorkbenchPage() {
  const { id = '' } = useParams();
  const [selection, setSelection] = useState<SelectionState>({ taskId: id, linkedExperimentCount: 0, resolved: false });
  const current = selection.taskId === id
    ? selection
    : { taskId: id, linkedExperimentCount: 0, resolved: false };

  useEffect(() => {
    let active = true;
    // Completed imports already have their authoritative experiment workbook.
    // Resolve that link before mounting the large source workbook: starting both
    // reads together can exhaust the document slots and block reopening a record.
    void dataApi.experimentSyncStatus(id, 'summary').then((sync) => {
      if (!active) return;
      const linked = sync.links.filter(link => Boolean(link.experimentId));
      const activeCount = sync.plan?.candidates.length ?? sync.links.filter(link => link.status !== 'SKIPPED').length;
      setSelection({ taskId: id, linkedExperimentCount: linked.length, resolved: true,
        experimentId: activeCount === 1 && linked.length === 1 ? linked[0]?.experimentId : undefined });
    }).catch(() => {
      if (active) setSelection({ taskId: id, linkedExperimentCount: 0, resolved: true });
    });
    return () => { active = false; };
  }, [id]);

  const openExperiment = useCallback((experimentId: string) => {
    setSelection((previous) => ({
      taskId: id,
      linkedExperimentCount: previous.taskId === id ? previous.linkedExperimentCount : 0,
      experimentId,
      resolved: true,
    }));
  }, [id]);

  const updateLinkedExperiments = useCallback((links: ExperimentAssemblyLink[]) => {
    const linkedExperimentCount = links.filter((link) => Boolean(link.experimentId)).length;
    setSelection((previous) => ({
      taskId: id,
      linkedExperimentCount,
      experimentId: previous.taskId === id ? previous.experimentId : undefined,
      resolved: true,
    }));
  }, [id]);

  const returnToImport = useCallback(() => {
    setSelection((previous) => ({
      taskId: id,
      linkedExperimentCount: previous.taskId === id ? previous.linkedExperimentCount : 0,
      resolved: true,
    }));
  }, [id]);

  if (!current.resolved) return <div className="data-workbench-loading"><Spin /></div>;

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
