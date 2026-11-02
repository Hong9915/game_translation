package com.gametrans.project;

import com.gametrans.common.NotFoundException;
import com.gametrans.config.AppProperties;
import com.gametrans.game.Game;
import com.gametrans.game.GameService;
import com.gametrans.storage.FileStorage;
import com.gametrans.job.TranslationJob;
import com.gametrans.job.TranslationJobRepository;
import com.gametrans.xlsx.CsvWorkbook;
import com.gametrans.xlsx.WorkbookInspector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final Set<String> ALLOWED = Set.of("xlsx", "xlsm", "xls", "csv", "tsv");

    private final ProjectRepository projects;
    private final FileStorage storage;
    private final WorkbookInspector inspector;
    private final AppProperties props;
    private final TranslationJobRepository jobs;

    private final com.gametrans.memory.MemoryService memory;
    private final GameService games;

    public ProjectService(ProjectRepository projects, FileStorage storage, WorkbookInspector inspector, AppProperties props,
                          TranslationJobRepository jobs, com.gametrans.memory.MemoryService memory, @org.springframework.context.annotation.Lazy GameService games) {
        this.memory = memory;
        this.games = games;
        this.projects = projects;
        this.storage = storage;
        this.inspector = inspector;
        this.props = props;
        this.jobs = jobs;
    }

    /** 프로젝트와 딸린 세그먼트·리비전·작업(DB cascade), 올린 파일과 내보낸 파일을 모두 지운다. */
    @Transactional
    public void delete(Long id) {
        Project p = get(id);
        if (jobs.findFirstByProjectIdAndStatus(id, TranslationJob.Status.RUNNING).isPresent())
            throw new IllegalArgumentException("작업이 진행 중입니다. 중지한 뒤 삭제하세요.");
        projects.delete(p);
        projects.flush();
        String dir = p.getStoragePath().substring(0, p.getStoragePath().lastIndexOf('/'));
        try { storage.deleteAll(dir); }
        catch (RuntimeException e) { log.warn("stored files for project {} could not be deleted ({})", id, dir, e); }
    }

    /** gameId나 gameName 중 하나는 있어야 한다. gameName은 없는 게임이면 새로 만든다. reviewOnly면 검수 전용 파일로 올린다. */
    @Transactional
    public Project upload(MultipartFile file, Long gameId, String gameName, boolean reviewOnly) {
        Game game = resolveGame(gameId, gameName);
        if (game == null) throw new IllegalArgumentException("파일을 올릴 게임을 선택하세요.");
        String rawName = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        String name = sanitizeFileName(rawName);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException(".xlsx · .xlsm · .xls · .csv · .tsv 파일만 올릴 수 있습니다.");
        if (file.getSize() > props.uploadMaxMb() * 1024L * 1024L) throw new IllegalArgumentException("최대 " + props.uploadMaxMb() + " MB까지 올릴 수 있습니다.");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw new UncheckedIOException(e); }
        boolean csv = ext.equals("csv") || ext.equals("tsv");
        if (csv) {
            // CSV는 시트 하나짜리 xlsx로 바꿔 저장한다. 파일명은 원래대로 두어 내보낼 때 다시 CSV로 돌려준다.
            try { bytes = CsvWorkbook.toXlsx(bytes, name); }
            catch (RuntimeException e) {
                log.warn("csv parse failed for {}", name, e);
                throw new IllegalArgumentException("CSV 파일을 읽을 수 없습니다: " + e.getMessage());
            }
        }
        WorkbookInspector.WorkbookInfo info;
        try { info = inspector.inspect(bytes); }
        catch (RuntimeException e) {
            log.warn("workbook inspection failed for {}", name, e);
            throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
        }

        String stored = csv ? name.substring(0, name.lastIndexOf('.')) + ".xlsx" : name;
        String path = "projects/" + UUID.randomUUID() + "/" + stored;
        storage.put(path, bytes);

        Project p = new Project();
        p.setFileName(name);
        p.setGame(game);
        p.setStoragePath(path);
        p.setSheetCount(info.sheets().size());
        p.setRowCount(info.totalRows());
        p.setReviewOnly(reviewOnly);
        return projects.save(p);
    }

    public List<Project> list(Long gameId) {
        return gameId == null ? projects.findAllByOrderByUploadedAtDesc() : projects.findByGameIdOrderByUploadedAtDesc(gameId);
    }

    public Project get(Long id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + id));
    }

    public byte[] originalBytes(Project p) { return storage.get(p.getStoragePath()); }

    @Transactional
    public Project patch(Long id, ProjectDtos.ProjectPatch patch) {
        Project p = get(id);
        Game next = resolveGame(patch.gameId(), patch.gameName());
        if (next != null && !next.getId().equals(p.getGame().getId())) {
            p.setGame(next);
            projects.flush();
            memory.backfill(id);  // 이미 확정한 용어·번역을 새 게임의 공유 자산으로
        }
        if (patch.currentStep() != null) {
            if (patch.currentStep() < 1 || patch.currentStep() > 6) throw new IllegalArgumentException("단계는 1~6 사이여야 합니다.");
            p.setCurrentStep(patch.currentStep());
        }
        return p;
    }

    /** 현재 단계를 최소 step으로 올린다(내리지는 않는다). */
    @Transactional
    public void bumpStep(Long id, int step) {
        Project p = get(id);
        if (p.getCurrentStep() < step) p.setCurrentStep(step);
    }

    private Game resolveGame(Long gameId, String gameName) {
        if (gameId != null) return games.get(gameId);
        if (gameName != null && !gameName.isBlank()) return games.findOrCreate(gameName);
        return null;
    }

    /** 업로드 파일명에서 경로 조각(디렉터리 순회 포함)을 제거하고 파일명만 남긴다. */
    public static String sanitizeFileName(String rawName) {
        String withoutDirs = rawName.replace('\\', '/');
        int slash = withoutDirs.lastIndexOf('/');
        String base = slash >= 0 ? withoutDirs.substring(slash + 1) : withoutDirs;
        Path fileName = Path.of(base).getFileName();
        String result = fileName == null ? "" : fileName.toString();
        return result.isBlank() ? "upload.xlsx" : result;
    }
}
