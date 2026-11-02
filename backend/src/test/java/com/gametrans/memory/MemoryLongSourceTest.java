package com.gametrans.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 배경 이야기처럼 색인 한도(2704바이트)를 넘는 긴 원문도 메모리에 저장된다(2026-10-13 프로드 정규화 500). */
class MemoryLongSourceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemoryService memory;
    @Autowired MemoryEntryRepository entries;

    @Test
    void remembersSourceLongerThanTheBtreeLimitAndKeepsItUnique() throws Exception {
        String game = "G-" + UUID.randomUUID().toString().substring(0, 8);
        long pid = TestProjects.upload(mvc, game);
        String src = incompressible(1500);   // 약 4500바이트
        assertThat(src.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(2704);

        memory.rememberAll(pid, Map.of(src, "엘프족에게는 전통이 있다."), MemoryEntry.Source.EXISTING, false);
        memory.rememberAll(pid, Map.of(src, "덮어쓴 문장"), MemoryEntry.Source.HUMAN, true);

        var saved = entries.findByGameNameAndSrcTextIn(game, java.util.List.of(src));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getKo()).isEqualTo("덮어쓴 문장");
    }

    @Test
    void normalizesASheetWhoseSourceAndExistingKoreanAreLong() throws Exception {
        String src = incompressible(1500), ko = incompressible(1200);
        byte[] xlsx;
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Skill");
            String[][] rows = {{"ID", "CN", "EN", "KR"}, {"001", src, "A long story", ko}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        var file = new org.springframework.mock.web.MockMultipartFile("file", "Herostory.xlsx", "", xlsx);
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/projects").file(file)
                .param("gameName", "G-" + UUID.randomUUID().toString().substring(0, 8))).andReturn().getResponse().getContentAsString();
        long pid = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/projects/{id}/normalize", pid)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** 반복 문장은 Postgres가 색인 전에 압축해 한도 안에 들어가므로, 실제 배경 이야기처럼 압축이 안 되는 한자로 만든다. */
    private static String incompressible(int chars) {
        java.util.Random rnd = new java.util.Random(chars);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars; i++) sb.append((char) (0x4E00 + rnd.nextInt(0x5000)));
        return sb.toString();
    }
}
