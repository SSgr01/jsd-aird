package com.jsd.aird.iam.api;
import java.util.UUID;
public interface DelegationIdentity {
 Identity delegationUser(UUID userId);
 record Identity(UUID id, UUID organizationId, String username, String roleCode, String status, long authVersion) {}
}
