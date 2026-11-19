package com.gametrans;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class TestWorkbooks {
    private TestWorkbooks() {}

    /** ItemText 시트: 헤더 + 6행(4행째 CN 비어 있음). Other 시트: 굵은 셀 + 병합. */
    public static byte[] sample() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("ItemText");
            String[][] rows = {
                {"ItemID", "Type", "CN_Text", "EN_Text"},
                {"IT_10001", "weapon", "烈焰之剑", "Blazing Sword"},
                {"IT_10002", "desc", "一把附有火焰之力的长剑，攻击时有概率造成灼烧。", "A longsword imbued with flame. Attacks may inflict Burn."},
                {"IT_10003", "weapon", "霜语者", "Frostwhisper"},
                {"IT_10004", "desc", "", "Restores {0} HP."},
                {"IT_10005", "desc", "使用后恢复{0}点生命值，冷却{1}秒。", "Restores {0} HP. Cooldown: {1}s."},
                {"IT_10006", "tag", "<color=#FFD700>传说</color> 品质装备", ""},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            Sheet other = wb.createSheet("Other");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle style = wb.createCellStyle();
            style.setFont(bold);
            Row r0 = other.createRow(0);
            r0.createCell(0).setCellValue("Bold title");
            r0.getCell(0).setCellStyle(style);
            other.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
            other.createRow(1).createCell(0).setCellValue("merged");
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * SkillExplain 형태: 이름(B/C/D)과 설명(E/F/G) 두 열 쌍. 2행 이름은 번역 있음·설명 빈칸,
     * 3행은 둘 다 EN·KR N/A(의도적), 4행은 이름 KR 빈칸·설명 KR N/A(영어 있음).
     */
    public static byte[] twoPairs() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Skill");
            String[][] rows = {
                {"技能编号", "技能名称_CN", "技能名称_EN", "技能名称_KR", "技能说明_CN", "技能说明_EN", "技能说明_KR"},
                {"211011101", "正义裁决", "Rule of Law", "정의의 심판", "解放圣剑的力量", "Unleash the holy sword", ""},
                {"211011001", "无", "N/A", "N/A", "无", "N/A", "N/A"},
                {"211011102", "专属天赋", "Exclusive Talent", "", "伤害减免提升至@10%@", "DMG Reduction increased to @10%@", "N/A"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 데이터 시트가 여럿인 파일. Items(3행)와 Skills(2행)는 행 번호가 서로 겹쳐서, 시트를 섞어 지우거나
     * 세면 바로 드러난다. 霜语者는 두 시트에 똑같이 나온다 — 용어 하나가 여러 시트의 행에 걸린다.
     * Quest는 폭(6열)·열 쌍 수(2개)·한국어 열 방식(기존 D열 + 새 열)이 다른 시트다 — 한국어 열과 플래그 열을
     * 시트마다 따로 계산하지 않고 한 번 계산해 돌려 쓰면 여기서 드러난다. 2행 KR_Name은 이미 차 있어
     * "기존 번역 유지" 행이 되고, 그 원문(霜语者)은 확정 용어이기도 하다.
     * "기타 시트"·"50%#특가"는 시트 이름에 한글·공백·`%`·`#`이 있어도 오가는지 보려고 둔다.
     */
    public static byte[] twoSheets() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Items"), new String[][]{
                {"ItemID", "CN_Text", "EN_Text"},
                {"IT_1", "烈焰之剑", "Blazing Sword"},
                {"IT_2", "霜语者", "Frostwhisper"},
                {"IT_3", "破晓之盾", "Dawnbreak Shield"},
            });
            write(wb.createSheet("Skills"), new String[][]{
                {"SkillID", "CN_Text", "EN_Text"},
                {"SK_1", "正义裁决", "Rule of Law"},
                {"SK_2", "霜语者", "Frostwhisper"},
            });
            write(wb.createSheet("Quest"), new String[][]{
                {"QuestID", "CN_Name", "EN_Name", "KR_Name", "CN_Desc", "EN_Desc"},
                {"QT_1", "霜语者", "Frostwhisper", "서리", "冒险开始", "The adventure begins"},
                {"QT_2", "破晓之盾", "Dawnbreak Shield", "", "限时特惠", "Limited-time deal"},
            });
            write(wb.createSheet("기타 시트"), new String[][]{
                {"ID", "CN_Text", "EN_Text"},
                {"ET_1", "冒险开始", "The adventure begins"},
            });
            write(wb.createSheet("50%#특가"), new String[][]{
                {"ID", "CN_Text", "EN_Text"},
                {"SA_1", "限时特惠", "Limited-time deal"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void write(Sheet s, String[][] rows) {
        for (int r = 0; r < rows.length; r++) {
            Row row = s.createRow(r);
            for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
        }
    }

    /** sample()과 같은 게임의 다른 파일: 2행은 烈焰之剑과 비슷한 새 문장, 3·4행은 sample()에 그대로 있는 문장. */
    public static byte[] variant() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("ItemText");
            String[][] rows = {
                {"ItemID", "Type", "CN_Text", "EN_Text"},
                {"IT_20001", "weapon", "烈焰之弓", "Blazing Bow"},
                {"IT_20002", "desc", "使用后恢复{0}点生命值，冷却{1}秒。", "Restores {0} HP. Cooldown: {1}s."},
                {"IT_20003", "weapon", "霜语者", "Frostwhisper"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 숫자 변형 용어가 든 파일: 星英雄과 그 변형 11星英雄·12星英雄, 기본 단어가 따로 없는 1级钻石秘境·2级钻石秘境,
     * 그리고 그 둘이 문장 안에 나오는 설명 한 줄. 열 배치는 sample()과 같아 DEFAULT_NORMALIZE로 정규화한다.
     */
    public static byte[] numericVariants() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("ItemText"), new String[][]{
                {"ItemID", "Type", "CN_Text", "EN_Text"},
                {"HT_1", "hero", "星英雄", "Star Hero"},
                {"HT_2", "hero", "11星英雄", "11-Star Hero"},
                {"HT_3", "hero", "12星英雄", "12-Star Hero"},
                {"HT_4", "zone", "1级钻石秘境", "Lv.1 Diamond Sanctum"},
                {"HT_5", "zone", "2级钻石秘境", "Lv.2 Diamond Sanctum"},
                {"HT_6", "item", "钻石", "Diamond"},
                {"HT_7", "desc", "击败12星英雄可进入1级钻石秘境", "Defeat a 12-star hero to enter the Lv.1 Diamond Sanctum"},
                {"HT_8", "zone", "7级黑暗城堡", "Lv.7 Dark Castle"},   // 기본 단어(黑暗城堡)가 없고 변형도 하나뿐 → 접지 않는다
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 이미 한국어 열이 있는 교정용 시트. 2행: 번역 있음, 3행: 원문 无·EN·KR 모두 N/A(안 쓰는 행),
     * 4행: KR만 N/A, 5행: KR 빈칸, 6행: 원문이 none, 7행: KR이 None.
     */
    public static byte[] withExistingKo() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Skill");
            String[][] rows = {
                {"SkillID", "CN", "EN", "KR"},
                {"211011101", "正义裁决", "Rule of Law", "정의의 심판"},
                {"211011001", "无", "N/A", "N/A"},
                {"211011102", "伤害减免提升至@10%@", "DMG Reduction increased to @10%@", "N/A"},
                {"211011103", "技能伤害提升至@420%@", "", ""},
                {"211011104", "none", "", "None"},
                {"211011105", "霜语者", "Frostwhisper", "None"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** withExistingKo()와 열 배치가 같다. 원문은 있는데 영어·한국어가 None·N/A — 아직 안 채운 칸이라 번역해야 한다(CardRepository 称号). */
    public static byte[] untranslatedNaRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Skill"), new String[][]{
                {"SkillID", "CN", "EN", "KR"},
                {"50100023", "风暴女王", "None", "None"},
                {"50100024", "对当前目标造成伤害", "N/A", "N/A"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** withExistingKo()와 열 배치는 같고, 2행과 6행의 원문(正义裁决)이 글자까지 똑같다 — 한 시트 안에 원문이 겹치는 행을 둔다. */
    public static byte[] withExistingKoSameSourceTwice() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Skill"), new String[][]{
                {"SkillID", "CN", "EN", "KR"},
                {"211011101", "正义裁决", "Rule of Law", "정의의 심판"},
                {"211011001", "对当前目标造成伤害", "N/A", "N/A"},
                {"211011102", "伤害减免提升至@10%@", "DMG Reduction increased to @10%@", "N/A"},
                {"211011103", "技能伤害提升至@420%@", "", ""},
                {"211011106", "正义裁决", "Rule of Law", "정의의 심판"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 升星(승급) 치환 시험용. 2·3·4행은 升星이 든 원문, 5행은 晋升→승급(원문 조건으로 걸러져야 함), 6행은 计划만. */
    public static byte[] upgradeRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Skill"), new String[][]{
                {"SkillID", "CN", "EN", "KR"},
                {"301", "升星计划", "Star-up plan", "승급 계획"},
                {"302", "英雄升星", "Hero star-up", "영웅 승급"},
                {"303", "升星材料", "Star-up material", "승급 재료"},
                {"304", "晋升奖励", "Promotion reward", "승급 보상"},
                {"305", "每日计划", "Daily plan", "일일 계획"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 단어장 추천 시험용. 礼包이 서로 다른 다섯 문장에 나오고, 6·7행은 숫자만 달라 한 문장으로 센다. */
    public static byte[] recommendRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Skill"), new String[][]{
                {"SkillID", "CN", "EN", "KR"},
                {"401", "领取礼包", "Claim pack", "패키지 수령"},
                {"402", "超值礼包", "Value pack", "초특가 패키지"},
                {"403", "礼包已售罄", "Pack sold out", "패키지 매진"},
                {"404", "每日礼包", "Daily pack", "일일 패키지"},
                {"405", "新手礼包1", "Starter pack 1", "초보자 패키지 1"},
                {"406", "新手礼包2", "Starter pack 2", "초보자 패키지 2"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * 표기 갈림 시험용. 陷阱은 함정(4행)/트랩(2행), 每日은 일일(2행)/매일(2행)/하루에(2행).
     * 一日游는 원문에 每日이 없는데 한국어에 "하루에"가 있다 — 통일이 건드리면 안 되는 행.
     * 野猪王은 멧돼지왕(3행)/멧돼지 왕(1행) — 띄어쓰기만 갈린 구절. 野猪 행들은 멧돼지왕이 주 표기로 뽑히게 한다.
     */
    public static byte[] variantRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Skill"), new String[][]{
                {"ID", "CN", "EN", "KR"},
                {"501", "放置陷阱", "Place trap", "함정을 설치"},
                {"502", "解除陷阱", "Disarm trap", "함정을 해제"},
                {"503", "躲避陷阱", "Dodge trap", "함정을 피하기"},
                {"504", "发现陷阱", "Find trap", "함정을 발견"},
                {"505", "陷阱伤害", "Trap damage", "트랩 피해"},
                {"506", "陷阱强化", "Trap upgrade", "트랩 강화"},
                {"601", "每日任务", "Daily quest", "일일 임무"},
                {"602", "每日礼包", "Daily pack", "일일 패키지"},
                {"603", "每日签到", "Daily check-in", "매일 출석"},
                {"604", "每日奖励", "Daily reward", "매일 보상"},
                {"605", "每日可领取一次", "Once a day", "하루에 한 번 받을 수 있음"},
                {"606", "每日刷新", "Daily refresh", "하루에 갱신"},
                {"607", "一日游", "Day trip", "하루에 끝나는 여행"},
                {"701", "击败野猪王", "Defeat Boar King", "멧돼지왕 처치"},
                {"702", "野猪王出现", "Boar King appears", "멧돼지왕 등장"},
                {"703", "野猪王宝箱", "Boar King chest", "멧돼지왕 상자"},
                {"704", "野猪王的愤怒", "Boar King's rage", "멧돼지 왕의 분노"},
                {"705", "野猪冲锋", "Boar charge", "멧돼지 돌진"},
                {"706", "小野猪", "Piglet", "작은 멧돼지"},
                {"707", "野猪群", "Boar herd", "멧돼지 무리"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 1글자 원문(WHOLE) 시험용. 801은 원문이 金 하나뿐인 행, 802는 金이 다른 낱말 속에 든 행(건드리면 안 된다). */
    public static byte[] wholeTermRows() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(wb.createSheet("Skill"), new String[][]{
                {"ID", "CN", "EN", "KR"},
                {"801", "金", "Gold", "금화"},
                {"802", "金币商店", "Coin shop", "금화 상점"},
            });
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 교정용: 한국어가 거의 다 차 있고 일부만 틀렸다. 2행 정상, 3행 오역, 4행 자리표시자 있음, 5행 빈칸. */
    public static byte[] proofread() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Code");
            String[][] rows = {
                {"file", "CN", "KR"},
                {"Login.ts", "是否退出游戏?", "게임을 종료하시겠습니까?"},
                {"Hero.ts", "属性", "재산"},
                {"Bag.ts", "{0}小时后过期", "{0} 시간 후에 만료됩니다."},
                {"Bag.ts", "分解成功", ""},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 최종 리뷰 파일: 원문(B)·기존 한국어(C)·이미 고친 한국어(D, KR_Fix). 같음 · 고침 · 기존 없이 고침 · 고친 칸 빔 · 둘 다 빔. */
    public static byte[] finalReview() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Sheet1");
            String[][] rows = {
                {"file_name", "Chinese String", "Korean String", "KR_Fix"},
                {"Login.ts", "是否退出游戏?", "게임을 종료하시겠습니까?", "게임을 종료하시겠습니까?"},
                {"Hero.ts", "属性", "재산", "속성"},
                {"Bag.ts", "分解成功", "", "분해 성공"},
                {"Bag.ts", "出售", "판매", ""},
                {"Bag.ts", "合成", "", ""},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** withExistingKo()와 같은 Skill 시트에 E열 KR_Fix를 더한 최종 리뷰 파일. 2행만 고쳐져 있다. */
    public static byte[] existingKoAsFinal() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Skill");
            String[][] rows = {
                {"SkillID", "CN", "EN", "KR", "KR_Fix"},
                {"211011101", "正义裁决", "Rule of Law", "정의의 심판", "정의의 판결"},
                {"211011106", "攻击", "Attack", "공격", "공격"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** existingKoAsFinal()과 같은 모양인데 1행·2행의 원문이 같다(최종 리뷰 레인 안 같은 원문 따라 바뀜 시험용). */
    public static byte[] finalSameSourceTwice() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Skill");
            String[][] rows = {
                {"SkillID", "CN", "EN", "KR", "KR_Fix"},
                {"1", "正义裁决", "Rule of Law", "정의의 심판", "정의의 심판"},
                {"2", "正义裁决", "Rule of Law", "정의의 심판", "정의의 심판"},
                {"3", "攻击", "Attack", "공격", "공격"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static final String[] FORM_HEADERS = {"角色", "道具", "技能", "地点", "怪物", "货币", "其他"};
    private static final String[] FORM_EXAMPLE = {"森之召唤师", "火焰之剑", "雷霆一击", "迷雾森林", "暗影狼王", "钻石", "公会"};

    /** 단어장 요청양식(术语表 시트)과 같은 모양: 1행 안내, 2행 머리글, 3행 "例：", 4행 예시(+H4 "← 示例"), 5행부터 입력. */
    public static byte[] termRequestForm(String[][] rowsFrom5) {
        return termRequestForm(FORM_EXAMPLE, rowsFrom5);
    }

    /** row4가 양식의 예시 줄을 대신한다. 각 행은 角色..其他 7칸이고 null/""은 빈칸. */
    public static byte[] termRequestForm(String[] row4, String[][] rowsFrom5) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("术语表");
            s.createRow(0).createCell(0).setCellValue("【填写说明】请将游戏中的专有名词按类别填入下方对应列。第4行灰色为示例，可删除或覆盖。");
            formRow(s, 1, FORM_HEADERS);
            String[] desc = new String[7];
            for (int i = 0; i < 7; i++) desc[i] = "例：" + FORM_HEADERS[i];
            formRow(s, 2, desc);
            formRow(s, 3, row4);
            s.getRow(3).createCell(7).setCellValue("← 示例");
            for (int r = 0; r < rowsFrom5.length; r++) formRow(s, 4 + r, rowsFrom5[r]);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void formRow(Sheet s, int rowIdx, String[] cells) {
        Row row = s.createRow(rowIdx);
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] != null && !cells[c].isEmpty()) row.createCell(c).setCellValue(cells[c]);
        }
    }
}
