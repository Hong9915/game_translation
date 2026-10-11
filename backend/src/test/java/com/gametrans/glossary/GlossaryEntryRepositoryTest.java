package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gametrans.IntegrationTest;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.glossary.GlossaryEntry.MatchMode;
import com.gametrans.game.GameService;
import com.gametrans.TestProjects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GlossaryEntryRepositoryTest extends IntegrationTest {
    @Autowired GlossaryEntryRepository repo;
    @Autowired GameService games;

    private Long newGame() { return games.findOrCreate(TestProjects.freshGame()).getId(); }

    private GlossaryEntry entry(Long gameId, Kind kind, String find, String ko) {
        GlossaryEntry e = new GlossaryEntry();
        e.setGameId(gameId);
        e.setKind(kind);
        e.setMatchMode(MatchMode.PARTIAL);
        e.setFind(find);
        e.setKo(ko);
        return e;
    }

    @Test
    void savesAndReadsBackAGameScopedEntry() {
        Long g = newGame();
        repo.save(entry(g, Kind.SRC, "红包", "행운 봉투"));
        assertThat(repo.findByGameId(g)).singleElement()
                .satisfies(e -> assertThat(e.getKo()).isEqualTo("행운 봉투"));
    }

    @Test
    void sharedEntriesHaveNoGame() {
        repo.save(entry(null, Kind.KO, "빨간 봉투 " + System.nanoTime(), "행운 봉투"));
        assertThat(repo.findByGameIdIsNull()).isNotEmpty();
    }

    @Test
    void theSameKindAndFindCannotRepeatInOneGame() {
        Long g = newGame();
        repo.saveAndFlush(entry(g, Kind.SRC, "红包", "행운 봉투"));
        assertThatThrownBy(() -> repo.saveAndFlush(entry(g, Kind.SRC, "红包", "빨간 봉투")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void theSameFindWithADifferentKindIsAllowed() {
        Long g = newGame();
        repo.saveAndFlush(entry(g, Kind.SRC, "红包", "행운 봉투"));
        repo.saveAndFlush(entry(g, Kind.KO, "红包", "행운 봉투"));
        assertThat(repo.findByGameId(g)).hasSize(2);
    }

    @Test
    void findByGameIdOrGameIdIsNullReturnsBothScopes() {
        Long g = newGame();
        Long g2 = newGame();
        GlossaryEntry gameScopedRow = entry(g, Kind.SRC, "红包", "행운 봉투");
        GlossaryEntry sharedRow = entry(null, Kind.SRC, "金币" + System.nanoTime(), "골드");
        GlossaryEntry g2Row = entry(g2, Kind.SRC, "红包", "행운 봉투");
        repo.save(gameScopedRow);
        repo.save(sharedRow);
        repo.save(g2Row);
        assertThat(repo.findByGameIdOrGameIdIsNull(g))
                .anyMatch(e -> e.getId().equals(gameScopedRow.getId()))
                .anyMatch(e -> e.getId().equals(sharedRow.getId()))
                .noneMatch(e -> e.getGameId() != null && e.getGameId().equals(g2));
    }
}
