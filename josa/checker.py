"""받침과 안 맞는 조사를 찾는다(설계 2026-10-07 §2). 상태 없이 문장만 본다."""
import re

from kiwipiepy import Kiwi

PAIR = {"을": "를", "를": "을", "이": "가", "가": "이", "은": "는", "는": "은", "과": "와", "와": "과"}
NEED_BATCHIM = {"을", "이", "은", "과"}
HANGUL_RUN = re.compile(r"[가-힣]+")
TAG = re.compile(r"<[^>]+>")
BR = re.compile(r"<br\s*/?>", re.I)
PARTICLE_HEAD = re.compile(r"(?:을|를|이|가|은|는|의|에|에서|에게|께|로|으로|와|과|도|만|까지|부터|처럼|보다|이나|나|랑|이랑)(?![가-힣])")
# 태그·대괄호·숫자·% 바로 뒤 조사. 조사 다음이 한글이면 조사가 아니다.
MARKED = re.compile(r"(</color>|\]|[0-9%])(으로|을|를|이|가|은|는|와|과|로)(?![가-힣])")
DIGIT = {"0": "영", "1": "일", "2": "이", "3": "삼", "4": "사", "5": "오", "6": "육", "7": "칠", "8": "팔", "9": "구"}
NUMBER_TAIL = re.compile(r"([0-9]+)(\.[0-9]+)?%?$")


def jong(ch):
    o = ord(ch) - 0xAC00
    return None if not 0 <= o < 11172 else o % 28


def fit(prev, p):
    """prev 글자 받침 뒤에 맞는 조사 꼴. ㄹ받침 뒤 '로'는 맞다."""
    j = jong(prev)
    if p in ("로", "으로"):
        return "로" if j in (0, 8) else "으로"
    return p if (p in NEED_BATCHIM) == (j != 0) else PAIR[p]


def last_sound(visible):
    """보이는 문자열 끝을 읽었을 때 마지막 음절. 판정할 수 없으면 None."""
    if visible.endswith("%"):
        return "트"
    m = NUMBER_TAIL.search(visible)
    if m:
        whole, frac = m.group(1), m.group(2)
        if frac:
            return DIGIT[frac[-1]]
        if whole == "0":
            return "영"
        zeros = len(whole) - len(whole.rstrip("0"))
        if zeros == 0:
            return DIGIT[whole[-1]]
        return {1: "십", 2: "백", 3: "천"}.get(zeros, "만")
    if visible and jong(visible[-1]) is not None:
        return visible[-1]
    return None


def utf16(text, i):
    # 깨진 문자(짝 없는 surrogate)가 있어도 죽지 않게 surrogatepass — 그런 글자도 UTF-16 한 칸이다
    return len(text[:i].encode("utf-16-le", "surrogatepass")) // 2


SENTENCE_END = re.compile(r"[.!?。\n]")
WINDOW = 80


def window(text, pos):
    """pos가 든 문장(앞뒤 WINDOW자 안). 후보마다 문장 전체를 분석하면 긴 셀에서 제곱으로 느려진다(2,800자 8초)."""
    lo = max(0, pos - WINDOW)
    ends = [m.end() for m in SENTENCE_END.finditer(text, lo, pos)]
    start = ends[-1] if ends else lo
    m = SENTENCE_END.search(text, pos, min(len(text), pos + WINDOW))
    end = m.end() if m else min(len(text), pos + WINDOW)
    return start, end


class Checker:
    def __init__(self, kiwi=None):
        self.kiwi = kiwi or Kiwi()  # 기본 cong-global: cong보다 가볍고(445MB vs 500MB) 판정은 같다

    def check(self, text, protected=frozenset()):
        hints = self._hangul(text, protected) + self._marked(text)
        hints.sort(key=lambda h: h["at"])
        for h in hints:
            h["at"] = utf16(text, h["at"])
        return hints

    def tag_space(self, text):
        """태그 경계 띄어쓰기(2026-10-08). 태그를 지운 보이는 글자를 Kiwi로 띄워 보고, 태그가 있던 경계에 공백이 새로 들어가면
        그 자리에만 공백을 넣는다 — 여는 태그면 그 앞, 닫는 태그면 그 뒤. 조사는 Kiwi가 붙여 두므로 띄워지지 않는다.
        보이는 글자의 다른 띄어쓰기는 건드리지 않는다. 바꿀 곳이 없으면 그대로 돌려준다."""
        runs = {}  # 보이는 글자 위치 → 그 경계에 붙은 태그 묶음의 text 안 (시작, 끝)
        visible, last = [], 0
        for m in TAG.finditer(text):
            visible.append(text[last:m.start()])
            at = sum(len(v) for v in visible)
            runs[at] = (runs.get(at, (m.start(),))[0], m.end())
            last = m.end()
        if not runs:
            return text
        visible.append(text[last:])
        visible = "".join(visible)
        inserted = self._inserted_spaces(visible)
        cuts = []
        for at, (start, end) in runs.items():
            if at not in inserted or at == 0 or at == len(visible):
                continue
            # 줄바꿈 경계(<br>, 글자 그대로의 \n)는 띄우지 않는다 — 줄 끝·줄 머리에 빈칸이 생긴다.
            # 숫자 바로 뒤도 — "2배로", "3중첩", "24을"처럼 단위·조사가 붙는데 Kiwi는 띄운다(실데이터)
            if BR.search(text, start, end) or visible[at - 2:at] == "\\n" or visible[at:at + 2] == "\\n" or visible[at - 1].isdigit():
                continue
            # 조사 꼴로 시작하면 띄우지 않는다 — 문장부호 뒤("보상!을")나 받침이 안 맞는 조사("영수을")는 Kiwi가 조사로 못 읽는다
            if PARTICLE_HEAD.match(visible, at):
                continue
            # 묶음 안 첫 여는 태그 앞에, 여는 태그가 없으면 묶음 끝(닫는 태그 뒤)에
            cut = next((m.start() for m in TAG.finditer(text, start, end) if not m.group().startswith("</")), end)
            cuts.append(cut)
        for cut in sorted(cuts, reverse=True):
            text = text[:cut] + " " + text[cut:]
        return text

    def _inserted_spaces(self, visible):
        """Kiwi가 visible에 새로 띄운 자리(그 뒤 글자의 visible 위치). %는 "퍼센트"로 읽혀 넣는다 — Kiwi는 "10%증가"를
        "10 %증가"로 띄워 % 뒤 경계를 못 잡는다."""
        proxy, origin = [], []  # proxy 글자마다 visible 위치(그 글자가 시작하는 조각이면 위치, 아니면 None)
        for i, ch in enumerate(visible):
            piece = "퍼센트" if ch == "%" else ch
            proxy.append(piece)
            origin.extend([i] + [None] * (len(piece) - 1))
        if not any("가" <= ch <= "힣" for ch in visible):
            return set()
        flat = "".join(proxy)
        spaced = self.kiwi.space(flat, reset_whitespace=False)
        out, j = set(), 0
        for ch in spaced:
            if j < len(flat) and ch == flat[j]:
                j += 1
            elif ch.isspace() and j < len(flat) and origin[j] is not None:
                out.add(origin[j])
        return out

    def _score(self, text):
        return self.kiwi.analyze(text, top_n=1)[0]

    def _tail_noun(self, word):
        toks = self.kiwi.tokenize(word)
        return bool(toks) and toks[-1].tag in ("NNG", "NNP") and toks[-1].end == len(word) and toks[-1].len >= 2

    def _hangul(self, text, protected):
        out = []
        befores = {}
        for m in HANGUL_RUN.finditer(text):
            w = m.group()
            if len(w) >= 3 and w.endswith("으로"):
                prev, p, pos = w[-3], "으로", m.end() - 2
            elif len(w) >= 2 and (w[-1] in PAIR or w[-1] == "로"):
                prev, p, pos = w[-2], w[-1], m.end() - 1
            else:
                continue
            alt = fit(prev, p)
            if alt == p or w in protected:
                continue
            ws, we = window(text, pos)
            if (ws, we) not in befores:
                befores[(ws, we)] = self._score(text[ws:we])
            before = befores[(ws, we)]
            after = self._score(text[ws:pos] + alt + text[pos + len(p):we])
            is_particle = any(t.start == pos - ws and t.tag.startswith("J") for t in after[0])
            if not is_particle or after[1] - before[1] <= 0:
                continue
            hint = {"at": m.start(), "from": w, "to": w[: len(w) - len(p)] + alt, "kind": "HANGUL"}
            if self._tail_noun(w):
                hint["dictNoun"] = True
            out.append(hint)
        return out

    def _marked(self, text):
        out = []
        for m in MARKED.finditer(text):
            p = m.group(2)
            visible = TAG.sub("", text[: m.start(2)])
            if visible.endswith("]"):
                inner = visible[visible.rfind("[") + 1: -1]
                if not inner or inner.isdigit():
                    continue  # [10025] 같은 ID 자리표시는 무엇이 들어갈지 모른다
                visible = inner
            sound = last_sound(visible)
            if sound is None:
                continue
            alt = fit(sound, p)
            if alt == p:
                continue
            kind = "NUMBER" if re.search(r"[0-9%]$", visible) else "TAG"
            out.append({"at": m.start(2), "from": p, "to": alt, "kind": kind})
        return out
