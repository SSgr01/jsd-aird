package com.jsd.aird.mdm.application.port;

import com.jsd.aird.mdm.domain.model.PartnerContact;
import com.jsd.aird.mdm.domain.model.PartnerStatus;
import com.jsd.aird.mdm.application.port.ContactProjectVector;

import java.util.List;
import java.util.UUID;

public interface PartnerContactRepository {
    void replaceContacts(UUID partnerId, List<PartnerContact> contacts, String operator);

    void insertContact(PartnerContact contact, String operator);

    boolean updateContact(PartnerContact contact, String operator);

    boolean updateContactStatus(UUID partnerId, UUID contactId, PartnerStatus status, long version, String operator);

    List<PartnerContact> findContacts(UUID partnerId);

    List<ContactProjectVector> findContactProjectVectors(UUID partnerId);
}
