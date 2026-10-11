import unittest

from checker import Checker

CHECKER = Checker()


def fixes(text, protected=frozenset()):
    return [(h["from"], h["to"], h["kind"]) for h in CHECKER.check(text, protected)]


class HangulTest(unittest.TestCase):
    def test_real_errors(self):
        self.assertIn(("생명를", "생명을", "HANGUL"), fixes("아군의 생명를 회복"))
        self.assertIn(("면역가", "면역이", "HANGUL"), fixes("피해 보너스와 피해 면역가 모두 10% 감소"))
        self.assertIn(("탄환로", "탄환으로", "HANGUL"), fixes("폭발 후 여러 개의 탄환로 분할"))
        self.assertIn(("퀘스트으로", "퀘스트로", "HANGUL"), fixes("레벨 퀘스트으로 변경되었습니다"))
        self.assertIn(("뽑기을", "뽑기를", "HANGUL"), fixes("토템 뽑기을 5번 완료하세요"))

    def test_words_that_merely_end_like_particles(self):
        for text in ["마을 재건", "가을 수확", "피해 증가", "권한 효과 해금", "사과 궁수 30마리",
                     "할 수 없는 일", "공격하지 않는 적", "물로 만든 방패", "서울로 간다"]:
            # 효과·사과처럼 Kiwi 점수가 오르는 낱말은 dictNoun이 붙어 백엔드가 거른다. 표시 없는 제안은 없어야 한다.
            bare = [h for h in CHECKER.check(text, frozenset()) if h["kind"] == "HANGUL" and not h.get("dictNoun")]
            self.assertEqual(bare, [], text)

    def test_dictionary_nouns_are_flagged_for_backend(self):
        # 장로·모험가는 Kiwi 점수만으로는 걸릴 수 있다. 백엔드가 말뭉치 빈도로 거르도록 dictNoun을 붙인다.
        for text in ["달팽이 장로 전리품", "전설 모험가"]:
            for h in CHECKER.check(text, frozenset()):
                if h["kind"] == "HANGUL":
                    self.assertTrue(h.get("dictNoun"), text)

    def test_protected_words(self):
        self.assertEqual(fixes("백소은 기본 공격탄", frozenset({"백소은"})), [])

    def test_followed_by_hangul_is_not_a_particle_slot(self):
        self.assertEqual(fixes("생명를로"), [])


class MarkedTest(unittest.TestCase):
    def test_numbers(self):
        self.assertIn(("로", "으로", "NUMBER"), fixes("소환 횟수가 <color=438800>3</color>로 증가"))
        self.assertIn(("로", "으로", "NUMBER"), fixes("벌레 수가 <color=438800>10</color>로 증가"))
        self.assertEqual(fixes("에너지 400을 회복"), [])
        self.assertEqual(fixes("방어력 파괴 20%를 부여"), [])
        self.assertIn(("으로", "로", "NUMBER"), fixes("회복계수가 <color=438800>72.4%</color>으로 증가"))

    def test_tags(self):
        self.assertIn(("을", "를", "TAG"), fixes("[방어력 증가][치명타 저항 증가]을 추가"))
        self.assertIn(("을", "를", "TAG"), fixes("싸울 수 있는<color=#ffae4e>영수</color>을 만나"))

    def test_not_judged(self):
        self.assertEqual(fixes("확률로 [10004]을 부여"), [])
        self.assertEqual(fixes("플레이어 ID를 입력"), [])


class RobustnessTest(unittest.TestCase):
    def test_long_text_stays_fast(self):
        # 후보 낱말마다 문장 전체를 두 번 분석하면 긴 셀에서 제곱으로 느려진다(리뷰 Important 1: 2,800자 8초)
        import time
        text = "마을 재건과 가을 수확 효과 증가. " * 140
        t0 = time.time()
        CHECKER.check(text, frozenset())
        self.assertLess(time.time() - t0, 2.0)

    def test_long_text_still_finds_errors(self):
        text = "마을 재건과 가을 수확 효과 증가. " * 60 + "아군의 생명를 회복. " + "마을 재건. " * 60
        self.assertIn(("생명를", "생명을", "HANGUL"), fixes(text))

    def test_lone_surrogate_does_not_crash(self):
        self.assertIsInstance(CHECKER.check("\ud800 생명를 회복", frozenset()), list)


class OffsetTest(unittest.TestCase):
    def test_offsets_are_utf16(self):
        text = "😀 생명를 회복"
        h = [h for h in CHECKER.check(text, frozenset()) if h["from"] == "생명를"][0]
        self.assertEqual(h["at"], 3)  # 😀는 UTF-16으로 2칸 + 공백 1칸

    def test_offset_points_at_from(self):
        text = "소환 횟수가 <color=438800>3</color>로 증가"
        h = CHECKER.check(text, frozenset())[0]
        self.assertEqual(text[h["at"]:h["at"] + len(h["from"])], h["from"])



class TagSpaceTest(unittest.TestCase):
    """태그 경계 띄어쓰기(2026-10-08): 태그를 지운 보이는 글자에서 Kiwi가 띄운 태그 경계에만 공백을 넣는다."""

    def test_word_split_at_tag_gets_space_outside_the_tag(self):
        self.assertEqual(CHECKER.tag_space("적에게<color=#FF0000>화염</color>피해를 준다"),
                         "적에게 <color=#FF0000>화염</color> 피해를 준다")

    def test_percent_before_closing_tag(self):
        # Kiwi는 "10%증가"를 "10 %증가"로 본다 — %를 낱말로 읽혀 % 뒤 경계를 잡는다
        self.assertEqual(CHECKER.tag_space("공격력이<color=#FFD700>10%</color>증가"),
                         "공격력이 <color=#FFD700>10%</color> 증가")

    def test_particle_after_tag_stays_attached(self):
        for text in ["<color=#FF0000>화염</color>을 입힌다", "<color=red>생명</color>이 회복된다",
                     "<color=red>3초</color> 동안 기절"]:
            self.assertEqual(CHECKER.tag_space(text), text)

    def test_spacing_away_from_tags_is_left_alone(self):
        # 태그 밖에서 Kiwi가 띄우고 싶은 곳(공격력이증가)은 건드리지 않는다
        self.assertEqual(CHECKER.tag_space("공격력이증가하고<color=red>화염</color>피해"),
                         "공격력이증가하고 <color=red>화염</color> 피해")

    def test_between_closing_and_opening_tag(self):
        self.assertEqual(CHECKER.tag_space("<color=red>화염</color><color=blue>냉기</color> 피해"),
                         "<color=red>화염</color> <color=blue>냉기</color> 피해")

    def test_number_keeps_its_unit_or_particle(self):
        # 실데이터: Kiwi가 "2 배로", "3 중첩", "24 을"로 띄운다 — 숫자 뒤엔 단위·조사가 붙으므로 띄우지 않는다
        for text in ["범위가<color=red>2</color>배로 증가", "한 번에<color=red>3</color>중첩에 겹쳐집니다",
                     "<color=red>24</color>을 휘두르며"]:
            self.assertNotIn("</color> ", CHECKER.tag_space(text), text)

    def test_line_breaks_are_not_spaced(self):
        # <br/>와 글자 그대로의 \n은 줄바꿈이다 — 그 옆에 공백을 두면 줄 끝·줄 머리에 빈칸이 생긴다
        for text in ["신수 시련<br/>190층 클리어", "클리어 <color=red>3333</color>\\n4. 클리어"]:
            self.assertEqual(CHECKER.tag_space(text).count(" "), text.count(" "), text)

    def test_particles_kiwi_misreads_stay_attached(self):
        # 실데이터: 문장부호 뒤("보상!을")나 받침이 안 맞는 조사("영수을")는 Kiwi가 조사로 못 읽고 띄운다
        for text in ["해금 후 <color=#00f944>초특가 보상!</color>을 획득할 수 있습니다", "함께 싸울 수 있는 <color=#ffae4e>영수</color>을 만나러 가자"]:
            self.assertEqual(CHECKER.tag_space(text), text)

    def test_space_already_inside_the_tag(self):
        # 태그 안쪽 끝에 공백이 있으면 보이는 글자는 이미 띄어져 있다 — 바깥에 또 넣으면 두 칸이 된다
        text = "팀 레벨이 <color=#c95050>50 및 </color>에 도달하면"
        self.assertEqual(CHECKER.tag_space(text), text)

    def test_no_tags_or_no_change(self):
        for text in ["", "공격력이증가", "<color=red>화염</color>", "적에게 <color=red>화염</color> 피해"]:
            self.assertEqual(CHECKER.tag_space(text), text)


if __name__ == "__main__":
    unittest.main()
