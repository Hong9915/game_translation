package com.gametrans.game;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 게임마다 한 장인 팀 메모. 아직 저장한 적 없으면 version 0, content null로 돌려준다. */
@Service
public class GameMemoService {
    static final int MAX_CONTENT_CHARS = 500_000;

    public record MemoDto(String content, long version, Instant updatedAt, String updatedByName) {}

    public record SaveMemoRequest(String content, Long baseVersion) {}

    private final JdbcTemplate jdbc;
    private final GameService games;

    public GameMemoService(JdbcTemplate jdbc, GameService games) {
        this.jdbc = jdbc;
        this.games = games;
    }

    public MemoDto get(Long gameId) {
        games.get(gameId);
        return read(gameId);
    }

    /**
     * baseVersion은 저장하는 쪽이 마지막으로 본 버전이다. 그 사이 다른 사람이 저장했으면 아무것도 바꾸지 않고 409로 거절한다.
     * 검사와 쓰기를 한 문장으로 해서 동시에 들어와도 한쪽만 통과한다. 트랜잭션은 돌려주는 version이 내 저장의 것이 되게 한다.
     */
    @Transactional
    public MemoDto save(Long gameId, SaveMemoRequest req, Long reviewerId) {
        games.get(gameId);
        if (req.content() == null || req.baseVersion() == null) throw new IllegalArgumentException("메모 내용과 버전이 필요합니다.");
        if (req.content().length() > MAX_CONTENT_CHARS) throw new IllegalArgumentException("메모가 너무 깁니다. 내용을 줄여 주세요.");
        int changed = jdbc.update("""
                insert into game_memo (game_id, content, version, updated_by, updated_at) values (?, ?, 1, ?, now())
                on conflict (game_id) do update
                  set content = excluded.content, version = game_memo.version + 1, updated_by = excluded.updated_by, updated_at = now()
                  where game_memo.version = ?
                """, gameId, req.content(), reviewerId, req.baseVersion());
        if (changed == 0) throw new IllegalStateException("다른 분이 먼저 메모를 고쳤습니다.");
        return read(gameId);
    }

    private MemoDto read(Long gameId) {
        List<MemoDto> rows = jdbc.query("""
                select m.content, m.version, m.updated_at, r.name
                from game_memo m left join reviewer r on r.id = m.updated_by
                where m.game_id = ?
                """, (rs, i) -> {
            Timestamp at = rs.getTimestamp("updated_at");
            return new MemoDto(rs.getString("content"), rs.getLong("version"), at.toInstant(), rs.getString("name"));
        }, gameId);
        return rows.isEmpty() ? new MemoDto(null, 0, null, null) : rows.get(0);
    }
}
