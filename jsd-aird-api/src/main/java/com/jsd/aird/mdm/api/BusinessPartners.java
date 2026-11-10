package com.jsd.aird.mdm.api;
import com.jsd.aird.shared.api.PageResponse;
public interface BusinessPartners {
 PartnerCommands.Created create(PartnerCommands.SavePartner command);
 PageResponse<BusinessPartner> findPage(String keyword, String industry, String customerLevel, String cooperationStatus, String owner, PartnerStatus status, int page, int size);
}
