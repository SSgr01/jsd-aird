package com.jsd.aird.mdm.api;
import java.util.UUID;
public interface PartnerContacts {
 PartnerCommands.Created addContact(UUID partnerId, PartnerCommands.SaveContact command);
}
