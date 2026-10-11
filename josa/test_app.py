import http.client
import json
import threading
import unittest
from http.server import HTTPServer

from app import Handler


class AppTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def post(self, body, chunked, path="/check"):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        data = json.dumps(body).encode("utf-8")
        conn.request("POST", path, body=iter([data]) if chunked else data,
                     headers={"Content-Type": "application/json"}, encode_chunked=chunked)
        return json.loads(conn.getresponse().read())

    def test_plain_body(self):
        r = self.post({"texts": ["아군의 생명를 회복", "마을"], "protected": []}, chunked=False)
        self.assertEqual(len(r["hints"]), 2)

    def test_chunked_body(self):
        # Spring RestClient(SimpleClientHttpRequestFactory)는 Content-Length 없이 chunked로 보낸다(2026-10-07 실데이터 확인에서 발견)
        r = self.post({"texts": ["아군의 생명를 회복", "마을"], "protected": []}, chunked=True)
        self.assertEqual(len(r["hints"]), 2)
        self.assertEqual(r["hints"][0][0]["to"], "생명을")

    def test_space(self):
        # 표기 갈림 띄어쓰기 카드의 표준 꼴(설계 2026-10-07 띄어쓰기 한꺼번에 통일). 의존명사 "간"·"주"는 띄어 쓴다
        r = self.post({"texts": ["서버간", "이번주", ""]}, chunked=True, path="/space")
        self.assertEqual(r["spaced"], ["서버 간", "이번 주", ""])


    def test_tag_space(self):
        # 태그 경계 띄어쓰기(2026-10-08). 바꿀 곳이 없으면 그대로
        r = self.post({"texts": ["적에게<color=red>화염</color>피해", "<color=red>화염</color>을", ""]}, chunked=True, path="/tag-space")
        self.assertEqual(r["spaced"], ["적에게 <color=red>화염</color> 피해", "<color=red>화염</color>을", ""])

if __name__ == "__main__":
    unittest.main()
