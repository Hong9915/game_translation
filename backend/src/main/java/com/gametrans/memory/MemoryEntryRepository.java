package com.gametrans.memory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {
    List<MemoryEntry> findByGameName(String gameName);
    List<MemoryEntry> findByGameNameAndSrcTextIn(String gameName, Collection<String> srcTexts);

    @Query("select m.gameName, count(m) from MemoryEntry m group by m.gameName")
    List<Object[]> countByGame();

    @Modifying
    @Query("update MemoryEntry m set m.gameName = :to where m.gameName = :from")
    void renameGame(String from, String to);

    void deleteByGameName(String gameName);
}
