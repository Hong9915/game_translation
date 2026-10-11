package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.IntegrationTest;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V15가 만든 스키마 — 인덱스와 단어장 표가 설계대로 생겼는지. */
class GlossarySchemaTest extends IntegrationTest {
    @Autowired DataSource ds;

    private boolean exists(String sql) throws Exception {
        try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Test
    void segmentHasMd5IndexOnSrcText() throws Exception {
        assertThat(exists("select count(*) from pg_indexes where tablename='segment' and indexname='segment_src_md5_idx'")).isTrue();
    }

    @Test
    void glossaryHasKindMatchModeAndNullableGameId() throws Exception {
        assertThat(exists("select count(*) from information_schema.columns where table_name='glossary' and column_name='kind'")).isTrue();
        assertThat(exists("select count(*) from information_schema.columns where table_name='glossary' and column_name='match_mode'")).isTrue();
        assertThat(exists("select count(*) from information_schema.columns where table_name='glossary' and column_name='find'")).isTrue();
        // 공유 단어집 항목은 game_id가 null이다
        assertThat(exists("select count(*) from information_schema.columns where table_name='glossary' and column_name='game_id' and is_nullable='YES'")).isTrue();
        // prev_ko는 없앴다
        assertThat(exists("select count(*) from information_schema.columns where table_name='glossary' and column_name='prev_ko'")).isFalse();
    }

    @Test
    void glossaryHasBothPartialUniqueIndexes() throws Exception {
        assertThat(exists("select count(*) from pg_indexes where tablename='glossary' and indexname='glossary_game_key'")).isTrue();
        assertThat(exists("select count(*) from pg_indexes where tablename='glossary' and indexname='glossary_shared_key'")).isTrue();
    }

    @Test
    void glossaryHasNullableOldKo() throws Exception {
        // V17: SRC+PARTIAL 항목이 "지금 번역에서 find를 옮긴 표기"를 기억한다
        assertThat(exists("select count(*) from information_schema.columns where table_name='glossary' and column_name='old_ko' and is_nullable='YES'")).isTrue();
    }
}
