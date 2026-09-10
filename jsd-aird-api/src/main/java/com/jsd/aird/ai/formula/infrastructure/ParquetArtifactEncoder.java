package com.jsd.aird.ai.formula.infrastructure;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Small deterministic Parquet encoder used for immutable model snapshot artifacts. */
@Component
public class ParquetArtifactEncoder {

    public byte[] encode(String recordName, List<Column> columns, List<Map<String, Object>> rows) {
        var schema = schema(recordName, columns);
        var output = new MemoryOutputFile();
        try (var writer = AvroParquetWriter.<GenericRecord>builder(output)
                .withSchema(schema)
                .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
                .build()) {
            for (var row : rows) {
                var record = new GenericData.Record(schema);
                for (var column : columns) record.put(column.name(), row.get(column.name()));
                writer.write(record);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("模型快照Parquet编码失败", exception);
        }
        return output.bytes();
    }

    private Schema schema(String name, List<Column> columns) {
        var fields = columns.stream().map(column -> {
            var valueType = switch (column.type()) {
                case STRING -> Schema.create(Schema.Type.STRING);
                case DOUBLE -> Schema.create(Schema.Type.DOUBLE);
            };
            var type = column.nullable()
                    ? Schema.createUnion(List.of(Schema.create(Schema.Type.NULL), valueType)) : valueType;
            return new Schema.Field(column.name(), type, null, column.nullable() ? Schema.Field.NULL_DEFAULT_VALUE : null);
        }).toList();
        var schema = Schema.createRecord(name, null, "com.jsd.aird.ai.snapshot", false);
        schema.setFields(fields);
        return schema;
    }

    public record Column(String name, Type type, boolean nullable) {
        public static Column string(String name) { return new Column(name, Type.STRING, false); }
        public static Column nullableString(String name) { return new Column(name, Type.STRING, true); }
        public static Column number(String name) { return new Column(name, Type.DOUBLE, false); }
        public static Column nullableNumber(String name) { return new Column(name, Type.DOUBLE, true); }
    }

    public enum Type { STRING, DOUBLE }

    private static final class MemoryOutputFile implements OutputFile {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean created;

        @Override public PositionOutputStream create(long blockSizeHint) throws IOException {
            if (created) throw new IOException("Parquet output already created");
            created = true;
            return stream();
        }

        @Override public PositionOutputStream createOrOverwrite(long blockSizeHint) {
            output.reset();
            created = true;
            return stream();
        }

        @Override public boolean supportsBlockSize() { return false; }
        @Override public long defaultBlockSize() { return 0; }
        byte[] bytes() { return output.toByteArray(); }

        private PositionOutputStream stream() {
            return new PositionOutputStream() {
                private long position;
                @Override public long getPos() { return position; }
                @Override public void write(int value) { output.write(value); position++; }
                @Override public void write(byte[] value, int offset, int length) {
                    output.write(value, offset, length);
                    position += length;
                }
                @Override public void flush() throws IOException { output.flush(); }
                @Override public void close() throws IOException { output.flush(); }
            };
        }
    }
}
