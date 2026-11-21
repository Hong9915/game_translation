package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 파이썬 OpenCC tw2s 결과(JSON Lines, {"in","out"})와 대조한다. 파일 경로는 SIMPLIFIER_ORACLE.
 * 번체가 없는 칸은 일부러 손대지 않으므로({@link Simplifier#toSimplified}) {@code hasTraditional}이 참인 칸만 비교한다.
 * SIMPLIFIER_ORACLE_DIFFS가 있으면 다른 칸과 손대지 않은 칸 중 OpenCC가 바꾸는 칸을
 * 그 파일에 JSON Lines({"kind","in","want","got"})로 쓴다(저장소 밖 경로에).
 */
@EnabledIfEnvironmentVariable(named = "SIMPLIFIER_ORACLE", matches = ".+")
class SimplifierOracleTest {

    @Test
    void matchesPythonTw2s() throws Exception {
        Simplifier s = new Simplifier();
        ObjectMapper om = new ObjectMapper();
        int all = 0;
        int total = 0;
        int guardedChangedByOpenCc = 0;
        List<String> diffs = new ArrayList<>();
        List<String> guarded = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getenv("SIMPLIFIER_ORACLE")))) {
            if (line.isBlank()) continue;
            JsonNode n = om.readTree(line);
            all++;
            String in = n.get("in").asText();
            String want = n.get("out").asText();
            if (!s.hasTraditional(in)) {
                assertThat(s.toSimplified(in)).isEqualTo(in);
                if (!want.equals(in)) {
                    guardedChangedByOpenCc++;
                    guarded.add(diffLine(om, "guarded", in, want, in));
                }
                continue;
            }
            total++;
            String got = s.toSimplified(in);
            if (!got.equals(want)) diffs.add(diffLine(om, "differ", in, want, got));
        }
        double rate = total == 0 ? 0 : 100.0 * (total - diffs.size()) / total;
        System.out.printf("oracle: %d cells, %d with traditional compared, %d differ, match %.3f%%; "
                + "%d cells left alone that OpenCC would change%n", all, total, diffs.size(), rate, guardedChangedByOpenCc);
        String diffPath = System.getenv("SIMPLIFIER_ORACLE_DIFFS");
        if (diffPath != null && !diffPath.isBlank()) {
            List<String> lines = new ArrayList<>(diffs);
            lines.addAll(guarded);
            Files.write(Path.of(diffPath), lines);
        }
        assertThat(total).isPositive();
        assertThat(rate).isGreaterThanOrEqualTo(99.9);
    }

    private static String diffLine(ObjectMapper om, String kind, String in, String want, String got) throws Exception {
        var d = om.createObjectNode();
        d.put("kind", kind);
        d.put("in", in);
        d.put("want", want);
        d.put("got", got);
        return om.writeValueAsString(d);
    }
}
