package com.jsd.aird.rnd.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import com.jsd.aird.rnd.application.port.QuotationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuotationRepository implements QuotationRepository {
    private static final String VISIBILITY = """
             AND (q.visibility = 'ALL' OR q.created_by = ? OR ? IN ('ADMIN', 'SYSTEM_ADMIN')
                  OR (q.visibility = 'RND' AND ? LIKE 'RND%')
                  OR (q.visibility = 'PROJECT' AND EXISTS (
                      SELECT 1 FROM iam.project_member pm
                      WHERE pm.organization_id = q.organization_id AND pm.project_id = q.project_id
                        AND pm.user_id = ?)))
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcQuotationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID create(UUID organizationId, UUID actorId, Draft draft) {
        var quotationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rnd.quotation_record
                (id, organization_id, quotation_no, customer_name, related_company, contact_name,
                 contact_title, phone, fax, email, address_line1, address_line2, website, note,
                 source_file_id, source_file_name, source_format, created_by,
                 template_version_id, project_id, project_name, stage_id, stage_name,
                 task_id, task_name, visibility)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, quotationId, organizationId, draft.quotationNo(), draft.customerName(),
                draft.relatedCompany(), draft.contactName(), draft.contactTitle(), draft.phone(),
                draft.fax(), draft.email(), draft.addressLine1(), draft.addressLine2(),
                draft.website(), draft.note(), draft.sourceFileId(), draft.sourceFileName(),
                draft.sourceFormat(), actorId, draft.templateVersionId(), draft.projectId(),
                draft.projectName(), draft.stageId(), draft.stageName(), draft.taskId(),
                draft.taskName(), draft.visibility());
        if (!draft.contacts().isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO rnd.quotation_contact
                    (id, organization_id, quotation_id, position, source_column,
                     contact_name, contact_title, phone)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, draft.contacts().stream()
                    .map(contact -> new Object[]{UUID.randomUUID(), organizationId, quotationId,
                            contact.position(), contact.sourceColumn(), contact.name(),
                            contact.title(), contact.phone()})
                    .toList());
        }
        if (!draft.items().isEmpty()) {
            var itemRows = new ArrayList<Object[]>();
            var priceRows = new ArrayList<Object[]>();
            for (var item : draft.items()) {
                var itemId = UUID.randomUUID();
                itemRows.add(new Object[]{itemId, organizationId, quotationId,
                        item.lineNo(), item.sourceRowNo(), item.productName(), item.unitPrice(),
                        item.specification(), item.quoteDate() == null ? null : Date.valueOf(item.quoteDate()),
                        item.customerCode(), item.note()});
                for (var option : item.priceOptions()) {
                    priceRows.add(new Object[]{UUID.randomUUID(), organizationId, itemId,
                            option.position(), option.unitPrice(), option.specification()});
                }
            }
            jdbc.batchUpdate("""
                    INSERT INTO rnd.quotation_item
                    (id, organization_id, quotation_id, line_no, source_row_no, product_name,
                     unit_price, specification, quote_date, customer_code, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, itemRows);
            if (!priceRows.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO rnd.quotation_item_price
                        (id, organization_id, quotation_item_id, position, unit_price, specification)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, priceRows);
            }
        }
        return quotationId;
    }

    @Override
    public PageResult<Summary> search(UUID organizationId, UUID userId, String role, Search search) {
        var page = Math.max(1, search.page());
        var size = Math.max(1, Math.min(200, search.size()));
        var where = new StringBuilder(" WHERE q.organization_id = ? AND q.deleted = false" + VISIBILITY);
        var args = new ArrayList<Object>();
        args.add(organizationId);
        args.add(userId);
        args.add(role);
        args.add(role);
        args.add(userId);
        var keyword = search.keyword() == null ? null : search.keyword().trim();
        if (keyword != null && !keyword.isEmpty()) {
            var like = "%" + keyword + "%";
            where.append("""
                     AND (q.quotation_no ILIKE ? OR q.customer_name ILIKE ?
                          OR coalesce(q.related_company, '') ILIKE ?
                          OR EXISTS (SELECT 1 FROM rnd.quotation_item i
                                     WHERE i.organization_id = q.organization_id
                                       AND i.quotation_id = q.id AND i.product_name ILIKE ?))
                    """);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        var customerName = search.customerName() == null ? null : search.customerName().trim();
        if (customerName != null && !customerName.isEmpty()) {
            where.append(" AND q.customer_name = ?");
            args.add(customerName);
        }
        if (search.dateFrom() != null) {
            where.append("""
                     AND EXISTS (SELECT 1 FROM rnd.quotation_item i
                                 WHERE i.organization_id = q.organization_id
                                   AND i.quotation_id = q.id AND i.quote_date >= ?)
                    """);
            args.add(Date.valueOf(search.dateFrom()));
        }
        if (search.dateTo() != null) {
            where.append("""
                     AND EXISTS (SELECT 1 FROM rnd.quotation_item i
                                 WHERE i.organization_id = q.organization_id
                                   AND i.quotation_id = q.id AND i.quote_date <= ?)
                    """);
            args.add(Date.valueOf(search.dateTo()));
        }
        var total = jdbc.queryForObject("SELECT count(*) FROM rnd.quotation_record q" + where,
                Long.class, args.toArray());
        var rows = jdbc.query("""
                SELECT q.id, q.quotation_no, q.customer_name, q.related_company, q.contact_name,
                       q.phone, q.source_file_id, q.source_file_name, q.created_at,
                       COALESCE((SELECT r.review_status FROM rnd.quotation_review r
                                 WHERE r.quotation_id = q.id AND r.organization_id = q.organization_id),
                                'NOT_SUBMITTED') AS review_status,
                       (SELECT count(*) FROM rnd.quotation_item i
                        WHERE i.organization_id = q.organization_id AND i.quotation_id = q.id) AS item_count,
                       (SELECT min(i.quote_date) FROM rnd.quotation_item i
                        WHERE i.organization_id = q.organization_id AND i.quotation_id = q.id) AS first_quote_date,
                       (SELECT max(i.quote_date) FROM rnd.quotation_item i
                        WHERE i.organization_id = q.organization_id AND i.quotation_id = q.id) AS last_quote_date
                FROM rnd.quotation_record q
                """ + where + " ORDER BY q.created_at DESC, q.quotation_no DESC LIMIT ? OFFSET ?",
                (result, row) -> summary(result), concat(args, List.of(size, (page - 1) * size)));
        var totalCount = total == null ? 0L : total;
        return new PageResult<>(rows, page, size, totalCount,
                size <= 0 ? 0 : (totalCount + size - 1) / size);
    }

    @Override
    public Optional<Detail> detail(UUID organizationId, UUID userId, String role, UUID quotationId) {
        var headers = jdbc.query("""
                SELECT q.id, q.quotation_no, q.customer_name, q.related_company, q.contact_name,
                       q.contact_title, q.phone, q.fax, q.email, q.address_line1, q.address_line2,
                       q.website, q.note, q.source_file_id, q.source_file_name, q.source_format, q.created_at,
                       q.workbook_snapshot, q.version_no,
                       COALESCE((SELECT r.review_status FROM rnd.quotation_review r
                                 WHERE r.quotation_id = q.id AND r.organization_id = q.organization_id),
                                'NOT_SUBMITTED') AS review_status,
                       (SELECT count(*) FROM rnd.quotation_item i
                        WHERE i.organization_id = q.organization_id AND i.quotation_id = q.id) AS item_count,
                       (SELECT min(i.quote_date) FROM rnd.quotation_item i
                        WHERE i.organization_id = q.organization_id AND i.quotation_id = q.id) AS first_quote_date,
                       (SELECT max(i.quote_date) FROM rnd.quotation_item i
                        WHERE i.organization_id = q.organization_id AND i.quotation_id = q.id) AS last_quote_date
                FROM rnd.quotation_record q
                WHERE q.organization_id = ? AND q.id = ? AND q.deleted = false
                """ + VISIBILITY, (result, row) -> new Object[]{
                summary(result), result.getString("contact_title"), result.getString("fax"),
                result.getString("email"), result.getString("address_line1"),
                result.getString("address_line2"), result.getString("website"),
                result.getString("note"), result.getString("source_format"),
                result.getString("workbook_snapshot"), result.getInt("version_no")
        }, organizationId, quotationId, userId, role, role, userId).stream().findFirst();
        if (headers.isEmpty()) return Optional.empty();
        var header = headers.get();
        var contacts = jdbc.query("""
                SELECT id, position, source_column, contact_name, contact_title, phone
                FROM rnd.quotation_contact
                WHERE organization_id = ? AND quotation_id = ?
                ORDER BY position
                """, (result, row) -> new Contact(result.getObject("id", UUID.class),
                result.getInt("position"), result.getInt("source_column"),
                result.getString("contact_name"), result.getString("contact_title"),
                result.getString("phone")), organizationId, quotationId);
        var items = jdbc.query("""
                SELECT id, line_no, source_row_no, product_name, unit_price, specification,
                       quote_date, customer_code, note
                FROM rnd.quotation_item
                WHERE organization_id = ? AND quotation_id = ?
                ORDER BY line_no
                """, (result, row) -> new Item(
                result.getObject("id", UUID.class), result.getInt("line_no"),
                result.getInt("source_row_no"),
                result.getString("product_name"), result.getString("unit_price"),
                result.getString("specification"),
                result.getObject("quote_date", java.time.LocalDate.class),
                result.getString("customer_code"), result.getString("note"), List.of()),
                organizationId, quotationId);
        var prices = jdbc.query("""
                SELECT p.id, p.quotation_item_id, p.position, p.unit_price, p.specification
                FROM rnd.quotation_item_price p
                JOIN rnd.quotation_item i ON i.organization_id = p.organization_id
                                         AND i.id = p.quotation_item_id
                WHERE p.organization_id = ? AND i.quotation_id = ?
                ORDER BY p.quotation_item_id, p.position
                """, (result, row) -> new Object[]{result.getObject("quotation_item_id", UUID.class),
                new PriceOption(result.getObject("id", UUID.class), result.getInt("position"),
                        result.getBigDecimal("unit_price"), result.getString("specification"))},
                organizationId, quotationId);
        var pricesByItem = new java.util.HashMap<UUID, List<PriceOption>>();
        for (var price : prices) {
            pricesByItem.computeIfAbsent((UUID) price[0], ignored -> new ArrayList<>())
                    .add((PriceOption) price[1]);
        }
        var detailedItems = items.stream().map(item -> new Item(item.id(), item.lineNo(),
                item.sourceRowNo(), item.productName(), item.unitPrice(), item.specification(),
                item.quoteDate(), item.customerCode(), item.note(),
                pricesByItem.getOrDefault(item.id(), List.of()))).toList();
        return Optional.of(new Detail((Summary) header[0], (String) header[1], (String) header[2],
                (String) header[3], (String) header[4], (String) header[5], (String) header[6],
                (String) header[7], (String) header[8], contacts, detailedItems,
                parseJson((String) header[9]), (int) header[10]));
    }

    @Override
    public int delete(UUID organizationId, UUID userId, String role, UUID quotationId) {
        return jdbc.update("""
                UPDATE rnd.quotation_record q SET deleted = true, updated_at = now()
                WHERE q.organization_id = ? AND q.id = ? AND q.deleted = false
                """ + VISIBILITY, organizationId, quotationId, userId, role, role, userId);
    }

    @Override
    public void retireReplaced(UUID organizationId, UUID quotationId) {
        jdbc.update("""
                UPDATE rnd.quotation_record
                SET deleted = true, updated_at = now()
                WHERE organization_id = ? AND id = ? AND deleted = false
                """, organizationId, quotationId);
    }

    @Override
    public int update(UUID organizationId, UUID userId, String role, UUID quotationId, Edit edit) {
        var updated = jdbc.update("""
                UPDATE rnd.quotation_record q SET customer_name=?, related_company=?, contact_name=?,
                    contact_title=?, phone=?, fax=?, email=?, address_line1=?, address_line2=?,
                    website=?, note=?, workbook_snapshot=?, version_no=version_no+1, updated_at=now()
                WHERE q.organization_id=? AND q.id=? AND q.deleted=false
                  AND q.version_no=?
                """ + VISIBILITY, edit.customerName().strip(), edit.relatedCompany(), edit.contactName(),
                edit.contactTitle(), edit.phone(), edit.fax(), edit.email(), edit.addressLine1(),
                edit.addressLine2(), edit.website(), edit.note(), json(edit.workbookSnapshot()),
                organizationId, quotationId, edit.versionNo(),
                userId, role, role, userId);
        if (updated == 0) return 0;
        jdbc.update("DELETE FROM rnd.quotation_contact WHERE organization_id=? AND quotation_id=?",
                organizationId, quotationId);
        jdbc.update("DELETE FROM rnd.quotation_item WHERE organization_id=? AND quotation_id=?",
                organizationId, quotationId);
        int position = 0;
        for (var contact : edit.contacts()) {
            jdbc.update("""
                    INSERT INTO rnd.quotation_contact
                    (id, organization_id, quotation_id, position, source_column, contact_name, contact_title, phone)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), organizationId, quotationId, ++position,
                    Math.max(1, contact.sourceColumn()), contact.name(), contact.title(), contact.phone());
        }
        int lineNo = 0;
        for (var item : edit.items()) {
            var itemId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO rnd.quotation_item
                    (id, organization_id, quotation_id, line_no, source_row_no, product_name,
                     unit_price, specification, quote_date, customer_code, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, itemId, organizationId, quotationId, ++lineNo,
                    Math.max(1, item.sourceRowNo()), item.productName().strip(), item.unitPrice(),
                    item.specification(), item.quoteDate() == null ? null : Date.valueOf(item.quoteDate()),
                    item.customerCode(), item.note());
            int pricePosition = 0;
            for (var price : item.priceOptions()) {
                jdbc.update("""
                        INSERT INTO rnd.quotation_item_price
                        (id, organization_id, quotation_item_id, position, unit_price, specification)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), organizationId, itemId, ++pricePosition,
                        price.unitPrice(), price.specification());
            }
        }
        return updated;
    }

    @Override
    public List<Version> versions(UUID organizationId, UUID quotationId) {
        return jdbc.query("""
                SELECT v.version_no,
                       CASE WHEN v.version_no = 1 THEN q.created_at ELSE v.created_at END AS created_at,
                       CASE WHEN v.version_no = 1 THEN q.created_by ELSE v.created_by END AS created_by,
                       COALESCE(u.display_name, u.username) AS created_by_name,
                       v.detail_jsonb
                FROM rnd.quotation_version v
                JOIN rnd.quotation_record q ON q.organization_id = v.organization_id AND q.id = v.quotation_id
                LEFT JOIN iam.app_user u ON u.organization_id = v.organization_id
                    AND u.id = CASE WHEN v.version_no = 1 THEN q.created_by ELSE v.created_by END
                WHERE v.organization_id=? AND v.quotation_id=? ORDER BY v.version_no DESC
                """, (rs, row) -> new Version(rs.getInt("version_no"),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                parseJson(rs.getString("detail_jsonb"))), organizationId, quotationId);
    }

    @Override
    public void saveVersion(UUID organizationId, UUID quotationId, UUID actorId, int versionNo, JsonNode detail) {
        jdbc.update("""
                INSERT INTO rnd.quotation_version
                    (id, organization_id, quotation_id, version_no, detail_jsonb, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, quotation_id, version_no) DO NOTHING
                """, UUID.randomUUID(), organizationId, quotationId, versionNo, json(detail), actorId);
    }

    private JsonNode parseJson(String value) {
        if (value == null) return null;
        try { return objectMapper.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("报价工作簿数据无效", exception);
        }
    }

    private PGobject json(JsonNode value) {
        if (value == null) return null;
        try {
            var json = new PGobject();
            json.setType("jsonb");
            json.setValue(value.toString());
            return json;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("报价工作簿无法序列化", exception);
        }
    }

    private Summary summary(ResultSet result) throws SQLException {
        return new Summary(result.getObject("id", UUID.class), result.getString("quotation_no"),
                result.getString("customer_name"), result.getString("related_company"),
                result.getString("contact_name"), result.getString("phone"),
                result.getObject("source_file_id", UUID.class), result.getString("source_file_name"),
                result.getInt("item_count"),
                result.getObject("first_quote_date", java.time.LocalDate.class),
                result.getObject("last_quote_date", java.time.LocalDate.class),
                result.getTimestamp("created_at").toInstant(), result.getString("review_status"));
    }

    private Object[] concat(List<Object> values, List<Object> tail) {
        var all = new ArrayList<>(values);
        all.addAll(tail);
        return all.toArray();
    }
}
