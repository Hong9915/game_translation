package com.gametrans.memory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GameTermRepository extends JpaRepository<GameTerm, Long> {
    Optional<GameTerm> findByGameNameAndSrcText(String gameName, String srcText);
    List<GameTerm> findByGameName(String gameName);
    List<GameTerm> findByGameNameAndSrcTextIn(String gameName, Collection<String> srcTexts);

    @Query("select g.gameName, count(g) from GameTerm g group by g.gameName")
    List<Object[]> countByGame();

    @Modifying
    @Query("update GameTerm g set g.gameName = :to where g.gameName = :from")
    void renameGame(String from, String to);

    void deleteByGameName(String gameName);
}
