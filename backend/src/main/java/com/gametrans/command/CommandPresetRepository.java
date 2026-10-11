package com.gametrans.command;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandPresetRepository extends JpaRepository<CommandPreset, Long> {
    List<CommandPreset> findByGameIdOrderByNameAsc(Long gameId);
    boolean existsByGameIdAndName(Long gameId, String name);
    Optional<CommandPreset> findByIdAndGameId(Long id, Long gameId);
}
