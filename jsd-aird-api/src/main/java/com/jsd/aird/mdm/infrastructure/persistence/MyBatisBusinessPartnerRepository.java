package com.jsd.aird.mdm.infrastructure.persistence;

import com.jsd.aird.mdm.application.port.BusinessPartnerRepository;
import com.jsd.aird.mdm.domain.model.*;
import com.jsd.aird.mdm.infrastructure.model.BusinessPartnerRow;
import com.jsd.aird.mdm.infrastructure.model.PartnerContactRow;
import com.jsd.aird.shared.api.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class MyBatisBusinessPartnerRepository implements BusinessPartnerRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BusinessPartnerMapper mapper;

    public MyBatisBusinessPartnerRepository(BusinessPartnerMapper mapper) {
        this.mapper = mapper;
    }

    public PageResponse<BusinessPartner> findPage(String k, PartnerStatus s, int p, int z) {
        var total = mapper.count(k, value(s));
        var rows = mapper.findPage(k, value(s), (p - 1) * z, z);
        var items = rows.stream().map(row -> toDomain(row, List.of())).toList();
        if (!items.isEmpty()) {
            var stats = mapper.selectPartnerStats(items.stream().map(BusinessPartner::id).toList()).stream()
                .collect(Collectors.toMap(BusinessPartnerMapper.PartnerStatsRow::id, Function.identity()));
            items = items.stream().map(bp -> {
                var st = stats.get(bp.id());
                if (st == null) return bp;
                List<String> owners = st.ownerNames() == null || st.ownerNames().isBlank()
                    ? List.of() : Arrays.asList(st.ownerNames().split(", "));
                return new BusinessPartner(bp.id(), bp.partnerCode(), bp.name(), bp.industry(), bp.address(),
                    bp.status(), bp.remark(), bp.contacts(), bp.customerLevel(), bp.cooperationStatus(),
                    bp.mainBusiness(), bp.customFields(), bp.version(), bp.createdAt(), bp.updatedAt(),
                    st.requirementCount(), st.projectCount(), st.latestFollowUpAt(), owners);
            }).toList();
        }
        return new PageResponse<>(items, p, z, total, (total + z - 1) / z);
    }

    public Optional<BusinessPartner> findById(UUID id) {
        return mapper.findById(id).map(row -> toDomain(row, List.of()));
    }

    public boolean existsByCodeOrNormalizedName(String c, String n, UUID x) {
        return mapper.exists(c, n, x);
    }

    public void insert(BusinessPartner p, String n, String o) {
        mapper.insert(p.id(), p.partnerCode(), p.name(), n, p.industry(), p.address(), p.status().name(), p.remark(),
            p.customerLevel(), p.cooperationStatus(), p.mainBusiness(), json(p.customFields()), p.createdAt(), o);
    }

    public boolean update(BusinessPartner p, String n, String o) {
        return mapper.update(p.id(), p.partnerCode(), p.name(), n, p.industry(), p.address(), p.remark(),
            p.customerLevel(), p.cooperationStatus(), p.mainBusiness(), json(p.customFields()),
            p.version(), Instant.now(), o) == 1;
    }

    public boolean updateStatus(UUID id, PartnerStatus s, long v, String o) {
        return mapper.updateStatus(id, s.name(), v, o) == 1;
    }

    @Override
    public List<PartnerContact> findContacts(UUID partnerId) {
        return List.of();
    }

    public void audit(UUID objectId, String action, String detail, String operator) {
        mapper.audit(UUID.randomUUID(), objectId, action, detail, operator);
    }

    private static BusinessPartner toDomain(BusinessPartnerRow r, List<PartnerContactRow> contacts) {
        return new BusinessPartner(r.id(), r.partnerCode(), r.name(), r.industry(), r.address(), PartnerStatus.valueOf(r.status()), r.remark(),
            contacts.stream().map(MyBatisBusinessPartnerRepository::toContact).toList(), r.customerLevel(),
            r.cooperationStatus(), r.mainBusiness(), parse(r.customFields()), r.version(), r.createdAt(), r.updatedAt());
    }

    private static PartnerContact toContact(PartnerContactRow r) {
        return new PartnerContact(r.id(), r.partnerId(), r.name(), r.department(), r.title(), r.phone(), r.email(),
            PartnerStatus.valueOf(r.status()), parseList(r.assignedProjectIds()), r.members(), r.wechat(),
            r.version(), parse(r.customFields()), r.createdAt(), r.updatedAt());
    }

    private static String value(Enum<?> e) {
        return e == null ? null : e.name();
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

    private static List<String> parseList(String value) {
        try {
            JsonNode node = JSON.readTree(value == null ? "[]" : value);
            var list = new ArrayList<String>();
            if (node.isArray()) {
                node.forEach(item -> list.add(item.asText()));
            }
            return list;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON stored in assigned_project_ids", exception);
        }
    }
}
