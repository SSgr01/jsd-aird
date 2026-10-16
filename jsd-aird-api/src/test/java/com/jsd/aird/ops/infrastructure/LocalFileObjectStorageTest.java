package com.jsd.aird.ops.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jsd.aird.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileObjectStorageTest {

    @TempDir
    Path storageRoot;

    @Test
    void writesAndReadsObjectsFromLocalDisk() throws Exception {
        var storage = new LocalFileObjectStorage(storageRoot.toString());
        var bytes = new byte[]{1, 3, 5, 7};
        var key = "organization/staged/file/sample.xlsx";

        storage.put(key, new ByteArrayInputStream(bytes), bytes.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        assertTrue(Files.exists(storageRoot.resolve(key)));
        try (var stored = storage.get(key)) {
            assertArrayEquals(bytes, stored.stream().readAllBytes());
        }
    }

    @Test
    void rejectsObjectKeysThatEscapeTheStorageRoot() {
        var storage = new LocalFileObjectStorage(storageRoot.toString());

        assertThrows(ApiException.class, () -> storage.get("../../outside.bin"));
    }
}
