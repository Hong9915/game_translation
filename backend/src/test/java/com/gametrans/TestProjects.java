package com.gametrans;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

public final class TestProjects {
    private TestProjects() {}

    /** 게임을 따로 정하지 않는 테스트용: 파일마다 새 게임을 만들어 용어·메모리가 테스트 사이에 새지 않게 한다. */
    public static String freshGame() { return "테스트 게임 " + java.util.UUID.randomUUID().toString().substring(0, 8); }

    public static long upload(MockMvc mvc) throws Exception { return upload(mvc, null); }

    public static long uploadExistingKo(MockMvc mvc) throws Exception { return uploadExistingKo(mvc, null); }

    public static long uploadExistingKo(MockMvc mvc, String gameName) throws Exception {
        return upload(mvc, "Skill_DB1.xlsx", TestWorkbooks.withExistingKo(), gameName);
    }

    /** untranslatedNaRows() — EXISTING_KO_NORMALIZE로 정규화한다. */
    public static long uploadUntranslatedNa(MockMvc mvc) throws Exception { return upload(mvc, "CardRepository.xlsx", TestWorkbooks.untranslatedNaRows(), null); }

    /** withExistingKo()와 같은 열 배치인데 한 시트 안에 원문이 겹치는 행(2행·6행)이 있다. */
    public static long uploadExistingKoSameSourceTwice(MockMvc mvc, String gameName) throws Exception {
        return upload(mvc, "Skill_DB2.xlsx", TestWorkbooks.withExistingKoSameSourceTwice(), gameName);
    }

    /** upgradeRows() — 升星/승급 원문 조건부 치환 시험용. withExistingKo()와 열 배치가 같아 EXISTING_KO_NORMALIZE로 정규화한다. */
    public static long uploadUpgrade(MockMvc mvc, String gameName) throws Exception {
        return upload(mvc, "Upgrade.xlsx", TestWorkbooks.upgradeRows(), gameName);
    }

    /** recommendRows() — 단어장 추천 시험용. EXISTING_KO_NORMALIZE로 정규화한다. */
    public static long uploadRecommend(MockMvc mvc, String gameName) throws Exception {
        return upload(mvc, "Packs.xlsx", TestWorkbooks.recommendRows(), gameName);
    }

    /** variantRows() — 표기 갈림·통일 시험용. EXISTING_KO_NORMALIZE로 정규화한다. */
    public static long uploadVariants(MockMvc mvc, String gameName) throws Exception {
        return upload(mvc, "Variants.xlsx", TestWorkbooks.variantRows(), gameName);
    }

    /** wholeTermRows() — 1글자 원문 WHOLE 시험용. EXISTING_KO_NORMALIZE로 정규화한다. */
    public static long uploadWholeTerm(MockMvc mvc, String gameName) throws Exception {
        return upload(mvc, "Coins.xlsx", TestWorkbooks.wholeTermRows(), gameName);
    }

    public static long upload(MockMvc mvc, String gameName) throws Exception { return upload(mvc, "ItemText_v3.xlsx", TestWorkbooks.sample(), gameName); }

    public static long uploadVariant(MockMvc mvc, String gameName) throws Exception { return upload(mvc, "ItemText_v4.xlsx", TestWorkbooks.variant(), gameName); }

    public static long uploadNumeric(MockMvc mvc, String gameName) throws Exception { return upload(mvc, "HeroText.xlsx", TestWorkbooks.numericVariants(), gameName); }

    public static long uploadExistingKoAsFinal(MockMvc mvc, String gameName) throws Exception { return upload(mvc, "Skill_final.xlsx", TestWorkbooks.existingKoAsFinal(), gameName); }

    /** existingKoAsFinal()을 최종 리뷰 버전으로(기존 D, 수정 E). */
    public static final String FINAL_SKILL_NORMALIZE = """
        {"sheetName":"Skill","headerRow":1,"keyCol":"A",
         "pairs":[{"srcCol":"B","enCol":"C","koColMode":"EXISTING_TO_NEW","koCol":"D","fixCol":"E"}]}
        """;

    /** finalReview() 워크북을 최종 리뷰 버전으로(원문 B, 기존 C, 수정 D). */
    public static final String FINAL_REVIEW_NORMALIZE = """
        {"sheetName":"Sheet1","headerRow":1,"keyCol":null,
         "pairs":[{"srcCol":"B","enCol":null,"koColMode":"EXISTING_TO_NEW","koCol":"C","koHeader":null,"fixCol":"D"}]}
        """;

    public static long uploadFinalReview(MockMvc mvc) throws Exception { return upload(mvc, "UI文本_261012(KR).xlsx", TestWorkbooks.finalReview(), null); }

    public static long uploadProofread(MockMvc mvc) throws Exception { return upload(mvc, "Code.xlsx", TestWorkbooks.proofread(), null); }

    /** 기존 KR 열(C)을 살린 채 교정만 하는 설정. */
    public static final String PROOFREAD_NORMALIZE = """
        {"sheetName":"Code","headerRow":1,"keyCol":"A","proofreadExisting":true,
         "pairs":[{"srcCol":"B","koColMode":"EXISTING","koCol":"C"}]}
        """;

    private static long upload(MockMvc mvc, String name, byte[] bytes, String gameName) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, "", bytes);
        var req = multipart("/api/projects").file(file);
        req.param("gameName", gameName == null ? freshGame() : gameName);
        String body = mvc.perform(req).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    /** 기존 KR 열(D)을 그대로 쓰는 설정. */
    public static final String EXISTING_KO_NORMALIZE = """
        {"sheetName":"Skill","headerRow":1,"keyCol":"A","retranslateExisting":false,
         "pairs":[{"srcCol":"B","enCol":"C","koColMode":"EXISTING","koCol":"D"}]}
        """;

    public static long uploadTwoPairs(MockMvc mvc, String gameName) throws Exception { return upload(mvc, "SkillExplain.xlsx", TestWorkbooks.twoPairs(), gameName); }

    /** 이름 쌍(B/C→D)과 설명 쌍(E/F→G) 둘 다 기존 KR 열에 쓴다. */
    public static final String TWO_PAIRS_NORMALIZE = """
        {"sheetName":"Skill","headerRow":1,"keyCol":"A","retranslateExisting":false,
         "pairs":[{"srcCol":"B","enCol":"C","koColMode":"EXISTING","koCol":"D"},
                  {"srcCol":"E","enCol":"F","koColMode":"EXISTING","koCol":"G"}]}
        """;

    public static long uploadTwoSheets(MockMvc mvc, String gameName) throws Exception { return upload(mvc, "MultiSheet.xlsx", TestWorkbooks.twoSheets(), gameName); }

    /**
     * twoSheets()의 Quest 시트 설정: 이름 쌍(B/C → 기존 D열)과 설명 쌍(E/F → 맨 끝 새 열) 둘.
     * Items·Skills(3열, 쌍 1개)와 열 배치가 달라서 시트별 열 계산이 필요하다.
     */
    public static final String QUEST_NORMALIZE = """
        {"sheetName":"Quest","headerRow":1,"keyCol":"A",
         "pairs":[{"srcCol":"B","enCol":"C","koColMode":"EXISTING","koCol":"D"},
                  {"srcCol":"E","enCol":"F","koColMode":"NEW","koHeader":"KO_Desc"}]}
        """;

    /** twoSheets()의 시트 하나짜리 설정들. 셋 다 같은 모양(A 키, B 원문, C 영어)이라 시트 이름만 다르다. */
    public static String sheetNormalize(String sheetName) {
        return """
            {"sheetName":"%s","headerRow":1,"keyCol":"A","pairs":[{"srcCol":"B","enCol":"C","koColMode":"NEW","koHeader":"KO"}]}
            """.formatted(sheetName);
    }

    public static final String DEFAULT_NORMALIZE = """
        {"sheetName":"ItemText","headerRow":1,"keyCol":"A","pairs":[{"srcCol":"C","enCol":"D","koColMode":"NEW","koHeader":"KO"}]}
        """;

    public static void normalize(MockMvc mvc, long id) throws Exception {
        mvc.perform(put("/api/projects/{id}/normalize", id).contentType(MediaType.APPLICATION_JSON).content(DEFAULT_NORMALIZE))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}
