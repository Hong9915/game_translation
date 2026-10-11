package com.gametrans.glossary;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlossaryCategoryRepository extends JpaRepository<GlossaryCategory, GlossaryCategory.Key> {
    List<GlossaryCategory> findByGameIdAndSrcIn(Long gameId, Collection<String> srcs);
}
