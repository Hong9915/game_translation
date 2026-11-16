package com.gametrans.rules;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StylePolicyRepository extends JpaRepository<StylePolicy, StylePolicy.Key> {
    List<StylePolicy> findByGameId(Long gameId);
}
