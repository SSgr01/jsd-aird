import type { ExperimentModel } from '@/services/experiments/experiment-api';

export interface ReviewInformation {
  purpose: string;
  mainConclusion: string;
}

export function reviewInformation(model: ExperimentModel): ReviewInformation {
  const conclusion = model.conclusion ?? {};
  return {
    purpose: typeof model.purpose === 'string' ? model.purpose : '',
    mainConclusion: typeof conclusion.mainConclusion === 'string' ? conclusion.mainConclusion : '',
  };
}

export function reviewInformationMissing(model: ExperimentModel) {
  const information = reviewInformation(model);
  return !information.purpose.trim() || !information.mainConclusion.trim();
}

export function withReviewInformation(
  model: ExperimentModel,
  information: ReviewInformation,
): ExperimentModel {
  return {
    ...model,
    purpose: information.purpose.trim(),
    conclusion: {
      ...(model.conclusion ?? {}),
      mainConclusion: information.mainConclusion.trim(),
    },
  };
}
