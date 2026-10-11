package com.gametrans.game;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {
    Optional<Game> findByName(String name);
    List<Game> findAllByOrderByCreatedAtDesc();
}
