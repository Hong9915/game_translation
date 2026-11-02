package com.gametrans.game;

import com.gametrans.common.NotFoundException;
import com.gametrans.game.GameDtos.GameDto;
import com.gametrans.memory.MemoryEntryRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.project.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameService {
    private final GameRepository games;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final MemoryEntryRepository entries;

    public GameService(GameRepository games, ProjectRepository projects, ProjectService projectService,
                       MemoryEntryRepository entries) {
        this.games = games;
        this.projects = projects;
        this.projectService = projectService;
        this.entries = entries;
    }

    public Game get(Long id) {
        return games.findById(id).orElseThrow(() -> new NotFoundException("게임을 찾을 수 없습니다: " + id));
    }

    /** 이름으로 찾고 없으면 만든다. gameName으로 올리는 옛 경로용. */
    @Transactional
    public Game findOrCreate(String name) {
        String clean = cleanName(name);
        return games.findByName(clean).orElseGet(() -> create(clean));
    }

    @Transactional
    public Game create(String name) {
        String clean = cleanName(name);
        if (games.findByName(clean).isPresent()) throw new IllegalArgumentException("이미 있는 게임 이름입니다: " + clean);
        Game g = new Game();
        g.setName(clean);
        return games.save(g);
    }

    /** 이름을 바꾸면 이름으로 묶인 번역 메모리도 같이 옮긴다. 프로젝트는 game_id로 붙어 있어 그대로. */
    @Transactional
    public Game rename(Long id, String name) {
        Game g = get(id);
        String clean = cleanName(name);
        if (clean.equals(g.getName())) return g;
        if (games.findByName(clean).isPresent()) throw new IllegalArgumentException("이미 있는 게임 이름입니다: " + clean);
        entries.renameGame(g.getName(), clean);
        g.setName(clean);
        return g;
    }

    /** DeepL 지시문을 바꾼다. 줄 수·글자 수 한도는 DeepLInstructions.normalize가 본다. */
    @Transactional
    public Game setDeepLInstructions(Long id, String text) {
        Game g = get(id);
        g.setDeeplInstructions(DeepLInstructions.normalize(text));
        return g;
    }

    /** 게임과 딸린 파일(저장 파일 포함), 번역 메모리를 모두 지운다. 진행 중인 작업이 있으면 거부한다. */
    @Transactional
    public void delete(Long id) {
        Game g = get(id);
        for (Project p : projects.findByGameIdOrderByUploadedAtDesc(id)) projectService.delete(p.getId());
        entries.deleteByGameName(g.getName());
        games.delete(g);
    }

    /** 최근에 파일을 올린 게임이 먼저, 파일이 없는 게임은 만든 순서대로 뒤에. */
    public List<GameDto> list() {
        Map<Long, long[]> projectAcc = new HashMap<>();   // [count]
        Map<Long, Instant> last = new HashMap<>();
        for (Object[] row : projects.countAndLastUploadByGame()) {
            Long gid = (Long) row[0];
            projectAcc.put(gid, new long[] {(Long) row[1]});
            last.put(gid, (Instant) row[2]);
        }
        Map<String, Long> memory = countMap(entries.countByGame());
        List<GameDto> out = new ArrayList<>();
        for (Game g : games.findAllByOrderByCreatedAtDesc()) {
            long[] pc = projectAcc.get(g.getId());
            out.add(new GameDto(g.getId(), g.getName(), pc == null ? 0 : pc[0],
                    memory.getOrDefault(g.getName(), 0L), last.get(g.getId()), g.getCreatedAt(), g.getDeeplInstructions()));
        }
        out.sort(Comparator.comparing((GameDto d) -> d.lastUploadedAt() == null ? Instant.EPOCH : d.lastUploadedAt()).reversed()
                .thenComparing(GameDto::createdAt, Comparator.reverseOrder()));
        return out;
    }

    public GameDto summary(Game g) {
        return list().stream().filter(d -> d.id().equals(g.getId())).findFirst()
                .orElse(new GameDto(g.getId(), g.getName(), 0, 0, null, g.getCreatedAt(), g.getDeeplInstructions()));
    }

    private static Map<String, Long> countMap(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : rows) out.put((String) row[0], (Long) row[1]);
        return out;
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("게임 이름을 입력하세요.");
        return name.trim();
    }
}
