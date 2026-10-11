"""조사 검사 보조 서버. 백엔드만 컨테이너 네트워크로 부른다(설계 §2)."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

from checker import Checker

CHECKER = Checker()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path not in ("/check", "/space", "/tag-space"):
            self._send(404, {"error": "not found"})
            return
        body = json.loads(self._body() or b"{}")
        texts = body.get("texts") or []
        if self.path == "/space":
            # 표기 갈림 띄어쓰기 카드의 표준 꼴. 공백을 지운 표기를 Kiwi 띄어쓰기 교정에 넣는다
            self._send(200, {"spaced": [self._space(t or "") for t in texts]})
            return
        if self.path == "/tag-space":
            # 태그 경계 띄어쓰기 후보(2026-10-08). 번역문 그대로 넣고, 태그 경계에만 공백을 더한 꼴을 받는다
            self._send(200, {"spaced": [self._tag_space(t or "") for t in texts]})
            return
        protected = frozenset(body.get("protected") or [])
        hints = [self._check(t or "", protected) for t in texts]
        self._send(200, {"hints": hints})

    @staticmethod
    def _space(text):
        try:
            return CHECKER.kiwi.space(text, reset_whitespace=True) if text else ""
        except Exception:
            return text

    @staticmethod
    def _tag_space(text):
        try:
            return CHECKER.tag_space(text)
        except Exception:
            return text

    @staticmethod
    def _check(text, protected):
        # 한 문장이 판정 중 터져도 묶음 전체가 실패하지 않게 그 문장만 제안 없음으로 돌린다 —
        # 묶음이 매번 실패하면 같은 행을 계속 다시 보내 검사가 멈춘다(리뷰 Important 1)
        try:
            return CHECKER.check(text, protected)
        except Exception:
            return []

    def _body(self):
        """Content-Length와 chunked 둘 다 읽는다 — Spring RestClient 기본 요청은 chunked로 온다."""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            out = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    return out
                out += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def _send(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    # Kiwi 하나를 한 스레드에서만 쓴다. 백엔드도 한 번에 한 요청만 보낸다.
    HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
