import type { QuotationReviewStatus } from '@/services/quotations/quotation-api';

export const quotationReviewLabels: Record<QuotationReviewStatus, string> = {
  NOT_SUBMITTED: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已通过',
  REJECTED: '已退回',
};

export const quotationReviewTones: Record<QuotationReviewStatus, string> = {
  NOT_SUBMITTED: 'not_started',
  SUBMITTED: 'pending',
  APPROVED: 'completed',
  REJECTED: 'cancelled',
};
