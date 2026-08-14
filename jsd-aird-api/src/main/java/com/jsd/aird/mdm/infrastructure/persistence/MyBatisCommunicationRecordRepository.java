package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.CommunicationRecordRepository;
import com.jsd.aird.mdm.domain.model.CommunicationRecord;
import com.jsd.aird.mdm.infrastructure.model.CommunicationRow;
import com.jsd.aird.shared.api.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisCommunicationRecordRepository implements CommunicationRecordRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CommunicationRecordMapper mapper;

    public MyBatisCommunicationRecordRepository(CommunicationRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResponse<CommunicationRecord> findCommunications(UUID partnerId, String status, int page, int size) {
        var total = mapper.count(partnerId, status);
        var rows = mapper.findPage(partnerId, status, (page - 1) * size, size);
        var items = rows.stream().map(MyBatisCommunicationRecordRepository::toDomain).toList();
        return new PageResponse<>(items, page, size, total, (total + size - 1) / size);
    }

    @Override
    public Optional<CommunicationRecord> findCommunication(UUID id) {
        return mapper.findById(id).map(r -> toDomain(r));
    }

    @Override
    public void insertCommunication(CommunicationRecord record, String operator) {
        mapper.insertCommunication(record.id(), record.recordCode(), record.name(), record.partnerId(),
            record.communicatedAt(), record.internalParticipants(), record.communicationMethod(), record.content(),
            record.status().name(), json(record.customFields()), Instant.now(), operator);
    }

    @Override
    public boolean updateCommunication(CommunicationRecord record, String operator) {
        return mapper.updateCommunication(record.id(), record.name(), record.communicatedAt(),
            record.internalParticipants(), record.communicationMethod(), record.content(),
            record.status().name(), json(record.customFields()), record.version(), operator) == 1;
    }

    @Override
    public boolean deleteCommunication(UUID id, long version) {
        return mapper.deleteCommunication(id, version) == 1;
    }

    private static CommunicationRecord toDomain(CommunicationRow r) {
        return new CommunicationRecord(r.id(), r.recordCode(), r.name(), r.partnerId(), r.communicatedAt(),
            r.internalParticipants(), r.communicationMethod(), r.content(),
            CommunicationRecord.CommunicationStatus.valueOf(r.status()),
            parse(r.customFields()), r.version(), r.createdAt(), r.updatedAt());
    }

    private static String json(JsonNode value) {
        return value == null || value.isNull() ? "{}" : value.toString();
    }

    private static JsonNode parse(String value) {
        try {
            return JSON.readTree(value == null ? "{}" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in custom_fields", exception);
        }
    }
}
