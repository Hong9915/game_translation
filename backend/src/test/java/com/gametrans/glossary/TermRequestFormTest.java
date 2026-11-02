package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gametrans.TestWorkbooks;
import com.gametrans.glossary.TermRequestForm.FormTerm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TermRequestFormTest {
    @Test
    void readsTermsByHeaderSkippingExampleRow() {
        byte[] xlsx = TestWorkbooks.termRequestForm(new String[][]{
            {"野猪王", null, null, null, null, "金币", null},
        });
        assertThat(TermRequestForm.parse(xlsx)).containsExactly(
            new FormTerm("CHARACTER", "野猪王"), new FormTerm("CURRENCY", "金币"));
    }

    @Test
    void keepsRow4WhenDeveloperOverwroteTheExample() {
        byte[] xlsx = TestWorkbooks.termRequestForm(
            new String[]{"野猪王", "火焰之剑", null, null, null, null, null}, new String[][]{});
        assertThat(TermRequestForm.parse(xlsx)).containsExactly(new FormTerm("CHARACTER", "野猪王"));
    }

    @Test
    void headerOrderDoesNotMatter() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("术语表");
            s.createRow(0).createCell(0).setCellValue("안내");
            String[] head = {"其他", "角色", "怪物", "货币", "地点", "技能", "道具"};
            Row h = s.createRow(1);
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            Row r = s.createRow(4);
            for (int i = 0; i < head.length; i++) r.createCell(i).setCellValue("词" + head[i]);
            wb.write(out);
            assertThat(TermRequestForm.parse(out.toByteArray())).containsExactly(
                new FormTerm("OTHER", "词其他"), new FormTerm("CHARACTER", "词角色"),
                new FormTerm("MONSTER", "词怪物"), new FormTerm("CURRENCY", "词货币"),
                new FormTerm("PLACE", "词地点"), new FormTerm("SKILL", "词技能"),
                new FormTerm("ITEM", "词道具"));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Test
    void trimsAndDedupesAcrossCategories() {
        byte[] xlsx = TestWorkbooks.termRequestForm(new String[][]{
            {" 陷阱 ", null, null, null, null, null, "陷阱"},
        });
        assertThat(TermRequestForm.parse(xlsx)).containsExactly(new FormTerm("CHARACTER", "陷阱"));
    }

    @Test
    void rejectsWorkbookWithoutFormHeaders() {
        assertThatThrownBy(() -> TermRequestForm.parse(TestWorkbooks.variantRows()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("단어장 요청양식이 아닙니다");
    }

    @Test
    void mapsToGlossaryCategories() {
        assertThat(TermRequestForm.glossaryCategory("CHARACTER")).isEqualTo("CHARACTER");
        assertThat(TermRequestForm.glossaryCategory("MONSTER")).isEqualTo("CHARACTER");
        assertThat(TermRequestForm.glossaryCategory("ITEM")).isEqualTo("ITEM");
        assertThat(TermRequestForm.glossaryCategory("CURRENCY")).isEqualTo("ITEM");
        assertThat(TermRequestForm.glossaryCategory("SKILL")).isEqualTo("SKILL");
        assertThat(TermRequestForm.glossaryCategory("PLACE")).isEqualTo("PLACE");
        assertThat(TermRequestForm.glossaryCategory("OTHER")).isEqualTo("NAME_OTHER");
        assertThat(List.copyOf(TermRequestForm.HEADERS.keySet())).hasSize(7);
    }
}
