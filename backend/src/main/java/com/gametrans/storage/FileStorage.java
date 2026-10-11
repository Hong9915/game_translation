package com.gametrans.storage;

public interface FileStorage {
    void put(String path, byte[] bytes);
    byte[] get(String path);
    /** prefix(디렉터리) 아래 파일을 모두 지운다. 없으면 조용히 넘어간다. */
    void deleteAll(String prefix);
}
