package com.gametrans.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(String rootDir) { this.root = Path.of(rootDir); }

    @Override public void put(String path, byte[] bytes) {
        try {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public byte[] get(String path) {
        try { return Files.readAllBytes(root.resolve(path)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public void deleteAll(String prefix) {
        Path dir = root.resolve(prefix);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
