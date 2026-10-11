package com.gametrans.glossary;

import com.gametrans.glossary.GlossaryEntry.Kind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlossaryEntryRepository extends JpaRepository<GlossaryEntry, Long> {
    List<GlossaryEntry> findByGameId(Long gameId);

    List<GlossaryEntry> findByGameIdIsNull();

    List<GlossaryEntry> findByGameIdOrGameIdIsNull(Long gameId);

    Optional<GlossaryEntry> findByGameIdAndKindAndFind(Long gameId, Kind kind, String find);

    Optional<GlossaryEntry> findByGameIdIsNullAndKindAndFind(Kind kind, String find);
}
