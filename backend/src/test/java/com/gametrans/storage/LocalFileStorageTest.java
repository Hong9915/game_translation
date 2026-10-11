package com.gametrans.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {
    @Test
    void putThenGetRoundTrips(@TempDir Path dir) {
        LocalFileStorage storage = new LocalFileStorage(dir.toString());
        storage.put("projects/abc/file.xlsx", new byte[] {1, 2, 3});
        assertThat(storage.get("projects/abc/file.xlsx")).containsExactly(1, 2, 3);
    }
}
