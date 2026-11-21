#!/usr/bin/env python3
"""시트가 많은 xlsx 한 개를 지금 있는 웹 API로 정규화해 보고 리뷰·내보내기 속도를 잰다.

쓰기:
  python scripts/measure_many_sheets.py --base http://localhost:8083 --file <xlsx> --game <새 게임 이름>
        [--pid <백엔드 JVM pid>] [--connector --reviewer <id>]

표준 출력에 `단계<TAB>초` 표를 찍는다. 게임 이름·시트 이름·셀 글은 출력하지 않는다(시트는 번호로만).
--pid를 안 주면 base 포트를 듣는 프로세스를 찾는다. 힙은 jcmd GC.heap_info를 1초마다 재서 최고값을 낸다.
"""
import argparse
import io
import re
import statistics
import subprocess
import sys
import threading
import time
from urllib.parse import urlparse

import openpyxl
import requests

KO = ["韩语", "韩文", "kr", "韓文", "韩国语"]
SIMP = ["简体中文", "简体", "简中"]
TRAD = ["繁体中文", "繁體", "繁体", "繁中"]
MIXED = ["简中/繁中", "中文"]
THRESHOLD = {"review_first": 2.0, "summary": 3.0, "export_preview": 10.0, "export": 60.0, "heap_gb": 1.5}


def kind_of(text):
    """헤더 글 하나가 어느 원문 종류인지: 'ko' | 'simp' | 'mixed' | 'trad' | None"""
    t = (text or "").strip().lower()
    if not t:
        return None
    if any(t == k or (not k.isascii() and k in t) for k in KO):
        return "ko"
    if t in MIXED or t == "简中/繁中":
        return "mixed"
    if "简中/繁中" in t:
        return "mixed"
    if any(k in t for k in SIMP):
        return "simp"
    if any(k in t for k in TRAD):
        return "trad"
    return None


def col_letter(idx):  # 1부터
    s = ""
    while idx:
        idx, r = divmod(idx - 1, 26)
        s = chr(65 + r) + s
    return s


def find_header(ws):
    """맨 위 6행에서 한국어 헤더와 원문 헤더가 함께 있는 행. (행, 원문 열, 한국어 열) 또는 None."""
    for r, row in enumerate(ws.iter_rows(min_row=1, max_row=6, values_only=True), start=1):
        kinds = {}
        for c, v in enumerate(row, start=1):
            k = kind_of(str(v) if v is not None else "")
            if k and k not in kinds:
                kinds[k] = c
        src = next((kinds[k] for k in ("simp", "mixed", "trad") if k in kinds), None)
        if "ko" in kinds and src:
            return r, col_letter(src), col_letter(kinds["ko"])
    return None


class HeapSampler(threading.Thread):
    def __init__(self, pid):
        super().__init__(daemon=True)
        self.pid, self.stop_ev = pid, threading.Event()
        self.peak_used = 0
        self.peak_committed = 0
        self.last = None
        self.lock = threading.Lock()
        self.ok = 0
        self.failed = 0  # 실패하거나 값이 비어 있던 표본 수

    @staticmethod
    def read(pid):
        out = subprocess.run(["jcmd", str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=20).stdout
        m = re.search(r"total (\d+)K, used (\d+)K", out)  # G1: " garbage-first heap   total 524288K, used 123K"
        if not m:
            m = re.search(r"total reserved (\d+)K, committed (\d+)K, used (\d+)K", out)
            return (int(m.group(3)) * 1024, int(m.group(2)) * 1024) if m else None
        return int(m.group(2)) * 1024, int(m.group(1)) * 1024

    def sample(self):
        try:
            v = self.read(self.pid)
        except Exception:
            v = None
        with self.lock:
            if v:
                self.ok += 1
                self.last = v
                self.peak_used = max(self.peak_used, v[0])
                self.peak_committed = max(self.peak_committed, v[1])
            else:
                self.failed += 1
        return v

    def run(self):
        while not self.stop_ev.is_set():
            self.sample()
            self.stop_ev.wait(1.0)


def find_pid(port):
    out = subprocess.run(["lsof", "-ti", "tcp:%d" % port, "-sTCP:LISTEN"], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def median_time(fn, n=3):
    ts = []
    for _ in range(n):
        t0 = time.perf_counter()
        fn()
        ts.append(time.perf_counter() - t0)
    return statistics.median(ts)


def mb(b):
    return "%.0f MB" % (b / 1048576.0)


# 백엔드 EmptyMarkers와 같다: 빈칸 대신 쓰는 표시도 빈 칸으로 친다
EMPTY_MARKERS = {"", "n/a", "na", "none", "null", "nil", "-", "tbd", "无", "待定", "暂无"}


def _has_han(t):
    return any("一" <= ch <= "鿿" or "㐀" <= ch <= "䶿" for ch in t)


def _has_hangul(t):
    return any("가" <= ch <= "힣" for ch in t)


def letter_idx(letter):  # 'A' -> 0
    v = 0
    for ch in letter:
        v = v * 26 + ord(ch) - 64
    return v - 1


def pick_columns(cols):
    """inspect의 열 통계로 [(원문 열, 한국어 열), ...]을 고른다. 못 고르면 None.

    원문 후보 = 한자 비율이 높고(최고값에서 10점 안, 30 이상) 한글이 적은 열. 그중 번체가 가장 적은 열(같으면 많이 채운 열, 같으면 왼쪽).
    한국어 후보 = 한글 비율이 가장 높은 열(30 이상). 아주 순수한(60 이상, 절반 이상 채운) 한자·한글 열 수가 같고 2개 이상이면
    가까운 열끼리 쌍으로 묶는다.
    """
    live = [c for c in cols if c["filled"] >= 10]
    han = [c for c in live if c["han"] >= 30 and c["hangul"] < 30]
    ko_all = [c for c in live if c["hangul"] >= 30 and c["han"] < 50]
    idx = lambda c: letter_idx(c["letter"])
    if not han or not ko_all:
        # 작은 시트는 헤더 칸이 비율을 흐려 위 기준에 안 걸린다: 한글이 가장 많은 열(같으면 오른쪽)을 한국어로,
        # 나머지 중 한자가 30 이상인 열(한자-한글 큰 순, 번체 적은 순, 왼쪽)을 원문으로 본다.
        ko_c = [c for c in live if c["hangul"] >= 30]
        if not ko_c:
            return None
        ko = sorted(ko_c, key=lambda c: (-c["hangul"], -idx(c)))[0]
        rest = [c for c in live if c is not ko and c["han"] >= 30]
        if not rest:
            return None
        src = sorted(rest, key=lambda c: (-(c["han"] - c["hangul"]), c["traditional"], idx(c)))[0]
        return [(src["letter"], ko["letter"])]
    top_han = max(c["han"] for c in han)
    cand = [c for c in han if c["han"] >= top_han - 10]
    src = sorted(cand, key=lambda c: (c["traditional"], -c["filled"], idx(c)))[0]
    ko = sorted(ko_all, key=lambda c: (-c["hangul"], -c["filled"], idx(c)))[0]
    strong_han = [c for c in han if c["han"] >= 60 and c["filled"] >= 50]
    strong_ko = [c for c in ko_all if c["hangul"] >= 60 and c["filled"] >= 50]
    if len(strong_han) >= 2 and len(strong_han) == len(strong_ko):
        free, pairs = list(strong_ko), []
        for h in sorted(strong_han, key=idx):
            k = min(free, key=lambda c: (abs(idx(c) - idx(h)), idx(c)))
            free.remove(k)
            pairs.append((h["letter"], k["letter"]))
        return pairs
    return [(src["letter"], ko["letter"])]


def pick_header_row(top, pairs):
    """top(맨 위 6행)에서 첫 데이터 행 바로 앞 행 번호. 데이터 행 = 첫 쌍의 원문 칸에 한자, 한국어 칸에 한글이 있는 첫 행.
    못 찾으면 맨 위 행에 두 칸 이상 글이 있으면 1, 아니면 0."""
    def cell(row, letter):
        i = letter_idx(letter)
        return (row[i] if i < len(row) and row[i] else "") or ""
    src, ko = pairs[0]
    for r, row in enumerate(top):
        if _has_han(cell(row, src)) and _has_hangul(cell(row, ko)):
            return r
    if top and sum(1 for v in top[0] if v and v.strip()) >= 2:
        return 1
    return 0


def run_connector(base, proj_id, reviewer, rec):
    """커넥터 경로: inspect(쪽) -> 열 고르기 -> normalize(40개씩) -> 경고 열이면 다시. (계획, 결과, 번호표, 다시 한 시트)."""
    hdr = {"X-Reviewer-Id": str(reviewer)}
    sheets, off, total = [], 0, None
    t0 = time.perf_counter()
    inspect_times = []
    while total is None or off < total:
        t1 = time.perf_counter()
        r = requests.get("%s/api/connector/projects/%d/inspect" % (base, proj_id), params={"offset": off, "limit": 50}, headers=hdr, timeout=600)
        r.raise_for_status()
        inspect_times.append(time.perf_counter() - t1)
        j = r.json()
        total = j["totalSheets"]
        if not j["sheets"]:
            break
        sheets += j["sheets"]
        off += len(j["sheets"])
    rec("inspect_calls", str(len(inspect_times)))
    rec("inspect_longest_call", max(inspect_times))
    rec("inspect_total", time.perf_counter() - t0)
    plans, no_plan = {}, []
    for sh in sheets:
        pairs = pick_columns(sh["columns"])
        if not pairs:
            no_plan.append(sh["index"])
            continue
        hr = pick_header_row(sh["top"], pairs)
        src_letters = [p[0] for p in pairs]
        trad = any(c["traditional"] > 0 and c["letter"] in src_letters for c in sh["columns"])
        plans[sh["name"]] = {"sheetName": sh["name"], "headerRow": hr, "keyCol": None,
                             "pairs": [{"srcCol": a_, "enCol": None, "koCol": k_} for a_, k_ in pairs], "srcToSimplified": trad}
    index_of = dict((sh["name"], sh["index"]) for sh in sheets)
    order = [sh["name"] for sh in sheets if sh["name"] in plans]
    rec("connector_sheets_total", str(len(sheets)))
    rec("connector_sheets_no_plan", str(len(no_plan)))
    if no_plan:
        print("# 열을 못 골라 건너뜀 시트 번호: %s" % no_plan)
    calls = []

    def call(batch, replace):
        t1 = time.perf_counter()
        r = requests.post("%s/api/connector/projects/%d/normalize" % (base, proj_id), json={"sheets": batch, "replace": replace}, headers=hdr, timeout=600)
        dt = time.perf_counter() - t1
        if not r.ok:
            raise RuntimeError("normalize HTTP %d" % r.status_code)
        calls.append(dt)
        return r.json()["results"]

    def run_all(pending, replace, final):
        tries = 0
        while pending:
            tries += 1
            if tries > 20:  # NOT_RUN이 계속 오면 끝없이 돌지 않게 한다
                raise RuntimeError("normalize 호출 20번을 넘겼는데 남은 시트가 있다(NOT_RUN 반복)")
            batch, pending = pending[:40], pending[40:]
            not_run = []
            for pl, rs in zip(batch, call(batch, replace)):
                if rs["status"] == "NOT_RUN":
                    not_run.append(pl)
                else:
                    final[pl["sheetName"]] = rs
            pending = not_run + pending

    final = {}
    run_all([plans[n] for n in order], False, final)
    redo, fallback_redo = [], []
    pat = re.compile(r"원문이 빈 (\d+)행은 ([A-Z]+)열에 글이 있습니다\.")
    for name, rs in list(final.items()):
        for w in rs.get("warnings") or []:
            m = pat.fullmatch(w)
            if m:
                pl = dict(plans[name])
                pl["pairs"] = [{"srcCol": m.group(2), "enCol": None, "koCol": pl["pairs"][0]["koCol"]}] + pl["pairs"][1:]
                pl["srcToSimplified"] = True
                plans[name] = pl
                redo.append(pl)
                fallback_redo.append(index_of[name])
    run_all(redo, True, final)

    status, warn_count, reasons, warn_sheets = {}, {}, {}, {}
    for name, rs in final.items():
        status[rs["status"]] = status.get(rs["status"], 0) + 1
        if rs.get("reason"):
            key = re.sub(r"\d+", "N", rs["reason"])
            key = re.sub(r"\(([A-Z]{1,2})\)", "(X)", key)
            reasons[key] = reasons.get(key, 0) + 1
        for w in rs.get("warnings") or []:
            key = re.sub(r"\d+", "N", w)
            key = re.sub(r"([A-Z]{1,2})열", "X열", key)
            key = re.sub(r"\(([A-Z]{1,2})\)", "(X)", key)
            warn_count[key] = warn_count.get(key, 0) + 1
            warn_sheets.setdefault(key, []).append(index_of[name])
    for k in ("OK", "REFUSED", "FAILED", "NOT_RUN"):
        rec("status_" + k, str(status.get(k, 0)))
    rec("normalize_calls", str(len(calls)))
    print("# normalize 호출별 초: %s" % " ".join("%.1f" % c for c in calls))
    rec("normalize_longest_call", max(calls) if calls else 0.0)
    rec("normalize_total", sum(calls))
    rec("sheets_renormalized_for_fallback_warning", str(len(fallback_redo)))
    if fallback_redo:
        print("# 경고 열로 다시 한 시트 번호: %s" % fallback_redo)
    for k, v in sorted(warn_count.items()):
        print("# 경고\t%d\t%s\t시트 %s" % (v, k, sorted(warn_sheets[k])))
    for k, v in sorted(reasons.items()):
        print("# 사유\t%d\t%s" % (v, k))
    st = lambda key: sum((rs.get("stats") or {}).get(key, 0) for rs in final.values())
    rec("rows_total_in_stats", str(st("totalRows")))
    rec("rows_normalized", str(st("normalized")))
    rec("rows_skipped_by_backend", str(st("skipped")))
    bad = [(index_of[n], rs["status"], re.sub(r"\s+", " ", rs.get("reason") or "")[:80]) for n, rs in final.items() if rs["status"] != "OK"]
    if bad:
        print("# OK가 아닌 시트(번호,상태,사유): %s" % bad)
    return plans, final, index_of, fallback_redo


def xml_last_rows(path):
    """시트 이름 -> 시트 xml의 마지막 <row r=...> 번호. openpyxl read_only는 dimension 밖의 빈 행을 못 본다."""
    import xml.etree.ElementTree as ET
    import zipfile
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
          "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}
    out = {}
    with zipfile.ZipFile(path) as z:
        wbx = ET.fromstring(z.read("xl/workbook.xml"))
        rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        target = dict((r.get("Id"), r.get("Target")) for r in rels)
        for sh in wbx.find("m:sheets", ns):
            t = target.get(sh.get("{%s}id" % ns["r"]))
            if not t:
                continue
            member = t.lstrip("/") if t.startswith("/") else "xl/" + t
            rows = re.findall(rb'<row [^>]*?\br="(\d+)"', z.read(member))
            out[sh.get("name")] = max((int(x) for x in rows), default=0)
    return out


def count_empty_src_rows(path, plans, final):
    """건너뛴 행이 원문 칸이 모두 빈 행뿐인지: 시트마다 (글이 있는 행 중) 쌍 원문 칸이 모두 빈 행 수를 세어 건너뛴 수와 비교한다."""
    xml_last = xml_last_rows(path)
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    diff_sheets, expected_total = [], 0
    for i, name in enumerate(wb.sheetnames, start=1):  # 시트 번호는 inspect와 같이 워크북 전체 순서
        ws = wb[name]
        pl, rs = plans.get(name), final.get(name)
        if not pl or not rs or not rs.get("stats"):
            continue
        pairs = [(letter_idx(p["srcCol"]), letter_idx(p["koCol"])) for p in pl["pairs"]]

        def blank(row, c):
            return c >= len(row) or row[c] is None or str(row[c]).strip() == ""

        def marker_or_blank(row, c):
            return c >= len(row) or row[c] is None or str(row[c]).strip().lower() in EMPTY_MARKERS

        def src_empty(row, s, k):
            # 백엔드 srcOrNull: 원문이 N/A·None 같은 표시여도 한국어 칸에 글이 있으면 원문으로 남긴다
            if blank(row, s):
                return True
            return marker_or_blank(row, s) and marker_or_blank(row, k)

        flags = []  # 행마다 건너뛸 행인가(쌍이 여럿이면 쌍마다 센다)
        seen = 0
        for row in ws.iter_rows(min_row=pl["headerRow"] + 1, values_only=True):
            seen += 1
            flags.extend(src_empty(row, s, k) for s, k in pairs)
        # openpyxl이 못 보는 끝의 빈 <row>도 백엔드(POI)는 행으로 센다: 빈 행이라 쌍마다 건너뛴다
        extra = max(0, (xml_last.get(name) or 0) - (pl["headerRow"] + seen))
        n = sum(flags) + extra * len(pairs)
        expected_total += n
        if n != rs["stats"]["skipped"]:
            diff_sheets.append((i, n, rs["stats"]["skipped"]))
    wb.close()
    return expected_total, diff_sheets


def full_compare(orig_path, new_bytes, plans):
    """원본과 내보내기 결과의 모든 칸을 대조. 다른 칸을 종류별로 센다(글은 찍지 않는다)."""
    import itertools
    orig = openpyxl.load_workbook(orig_path, read_only=True, data_only=True)
    new = openpyxl.load_workbook(io.BytesIO(new_bytes), read_only=True, data_only=True)
    res = {"sheets_orig": len(orig.sheetnames), "sheets_new": len(new.sheetnames), "order_same": orig.sheetnames == new.sheetnames,
           "cells": 0, "diff": 0, "diff_ko_col": 0, "diff_src_col": 0, "diff_other_col": 0, "diff_ws_only": 0, "diff_type": {}, "diff_sheets": []}
    for si, name in enumerate(orig.sheetnames, start=1):
        if name not in new.sheetnames:
            continue
        pl = plans.get(name)
        roles = {}
        if pl:
            for p in pl["pairs"]:
                roles[p["srcCol"]] = "src"
                roles[p["koCol"]] = "ko"
        before = res["diff"]
        for orow, nrow in itertools.zip_longest(orig[name].iter_rows(values_only=True), new[name].iter_rows(values_only=True), fillvalue=()):
            for c in range(max(len(orow), len(nrow))):
                ov = orow[c] if c < len(orow) else None
                nv = nrow[c] if c < len(nrow) else None
                res["cells"] += 1
                if ov == nv or (ov in (None, "") and nv in (None, "")):
                    continue
                res["diff"] += 1
                res["diff_%s_col" % roles.get(col_letter(c + 1), "other")] += 1
                if isinstance(ov, str) and isinstance(nv, str) and ov.strip() == nv.strip():
                    res["diff_ws_only"] += 1
                k = "%s->%s" % (type(ov).__name__, type(nv).__name__)
                res["diff_type"][k] = res["diff_type"].get(k, 0) + 1
        if res["diff"] > before:
            res["diff_sheets"].append(si)
    orig.close()
    new.close()
    return res


def connector_main(a, base, sampler, rec0, pid_proj, game_id):
    vals_t = {}

    def rec(name, val):  # 찍으면서 원래 값도 모아 둔다(문턱 판정용)
        vals_t[name] = val
        rec0(name, val)

    plans, final, index_of, redo = run_connector(base, pid_proj, a.reviewer, rec)
    heap_after_norm = sampler.sample() if sampler else None
    rec("review_first_page", median_time(lambda: requests.get(
        "%s/api/games/%d/review/rows?page=0&size=50" % (base, game_id), timeout=300).raise_for_status()))
    rec("review_summary", median_time(lambda: requests.get(
        "%s/api/games/%d/review/rows/summary" % (base, game_id), timeout=300).raise_for_status()))
    rec("export_preview", median_time(lambda: requests.get(
        "%s/api/projects/%d/export/preview" % (base, pid_proj), timeout=300).raise_for_status()))
    opts = {"addFlagColumn": False, "addHistorySheet": False, "addGlossarySheet": False, "overwriteKo": True, "addNoteColumn": False}
    last = {}

    def do_export():
        rr = requests.post("%s/api/projects/%d/export" % (base, pid_proj), json=opts, timeout=600)
        rr.raise_for_status()
        last["bytes"] = rr.content

    rec("export", median_time(do_export))
    heap_after_export = sampler.sample() if sampler else None

    # 시트 순서: 게임 시트 목록 == 워크북 순서(정규화한 시트만 부분열)
    r = requests.get("%s/api/connector/games/%d/sheets" % (base, game_id), timeout=60)
    r.raise_for_status()
    grid = [s["sheet"] for s in r.json()]
    wb = openpyxl.load_workbook(a.file, read_only=True)
    names = wb.sheetnames
    wb.close()
    expect = [n for n in names if n in final and final[n]["status"] == "OK"]
    rec("grid_sheets", str(len(grid)))
    rec("grid_order_equals_workbook_order", str(grid == expect))
    if grid != expect:
        print("# 순서가 다른 위치(번호): %s" % [i for i, (x, y) in enumerate(zip(grid, expect), start=1) if x != y][:20])

    exp_skip, diff_sheets = count_empty_src_rows(a.file, plans, final)
    rec("skipped_rows_backend", str(sum((rs.get("stats") or {}).get("skipped", 0) for rs in final.values())))
    rec("empty_src_rows_counted_in_workbook", str(exp_skip))
    rec("sheets_where_skipped_differs_from_empty_src_rows", str(len(diff_sheets)))
    if diff_sheets:
        print("# (시트 번호, 센 값, 건너뛴 값): %s" % diff_sheets[:30])

    cmp_ = full_compare(a.file, last["bytes"], plans)
    rec("compare_sheet_count_same", str(cmp_["sheets_orig"] == cmp_["sheets_new"]))
    rec("compare_sheet_order_same", str(cmp_["order_same"]))
    rec("compare_cells", str(cmp_["cells"]))
    rec("compare_cells_differing", str(cmp_["diff"]))
    rec("compare_differing_ko_col", str(cmp_["diff_ko_col"]))
    rec("compare_differing_src_col", str(cmp_["diff_src_col"]))
    rec("compare_differing_other_col", str(cmp_["diff_other_col"]))
    rec("compare_differing_whitespace_only", str(cmp_["diff_ws_only"]))
    if cmp_["diff"]:
        print("# 다른 칸 값 형: %s" % cmp_["diff_type"])
        print("# 다른 칸이 있는 시트 번호: %s" % cmp_["diff_sheets"][:40])

    if sampler:
        sampler.stop_ev.set()
        rec("heap_after_normalize_used", mb(heap_after_norm[0]) if heap_after_norm else "?")
        rec("heap_after_export_used", mb(heap_after_export[0]) if heap_after_export else "?")
        rec("heap_peak_used_sampled", mb(sampler.peak_used))
        rec("heap_peak_committed_sampled", mb(sampler.peak_committed))
        rec("heap_samples_ok", str(sampler.ok))
        rec("heap_samples_failed_or_empty", str(sampler.failed))
    sts = [rs["status"] for rs in final.values()]
    v = {"longest_call": vals_t["normalize_longest_call"], "heap_peak_bytes": sampler.peak_used if sampler and sampler.ok else None,
         "review_first": vals_t["review_first_page"], "summary": vals_t["review_summary"], "preview": vals_t["export_preview"],
         "export": vals_t["export"], "diff_cells": cmp_["diff"], "count_same": cmp_["sheets_orig"] == cmp_["sheets_new"],
         "order_same": cmp_["order_same"], "grid_order_same": grid == expect, "failed": sts.count("FAILED"), "refused": sts.count("REFUSED"),
         "skipped_diff_sheets": len(diff_sheets)}
    print("\n# 문턱 판정")
    ok_all = print_verdict(judge(v))
    no_plan = int(vals_t["connector_sheets_no_plan"])
    if no_plan:
        print("# 안내: 열 계획을 못 세워 보내지 않은 시트 %d개 — 대역 휴리스틱의 한계(FAIL 아님). 실제 Claude는 이런 시트를 따로 본다." % no_plan)
    print("# project_id=%d game_id=%d" % (pid_proj, game_id))
    return 0 if ok_all else 1


def judge(v):
    """문턱을 판정해 [(이름, 통과?, 설명)]을 돌려준다. 순수 함수(단위 확인용)."""
    heap = v.get("heap_peak_bytes")
    rows = [
        ("가장 긴 커넥터 호출", v["longest_call"] <= 60.0, "%.2f s <= 60 s" % v["longest_call"]),
        ("힙 최고", heap is not None and heap <= 1.5 * 1073741824, "표본 없음" if heap is None else "%.2f GB <= 1.5 GB" % (heap / 1073741824.0)),
        ("리뷰 첫 쪽", v["review_first"] <= 2.0, "%.2f s <= 2 s" % v["review_first"]),
        ("리뷰 요약", v["summary"] <= 3.0, "%.2f s <= 3 s" % v["summary"]),
        ("내보내기 미리 보기", v["preview"] <= 10.0, "%.2f s <= 10 s" % v["preview"]),
        ("내보내기", v["export"] <= 60.0, "%.2f s <= 60 s" % v["export"]),
        ("다른 칸", v["diff_cells"] == 0, "%d == 0" % v["diff_cells"]),
        ("시트 수 같음", v["count_same"], str(v["count_same"])),
        ("시트 순서 같음", v["order_same"], str(v["order_same"])),
        ("표 시트 순서 = 워크북 순서", v["grid_order_same"], str(v["grid_order_same"])),
        ("FAILED 0", v["failed"] == 0, str(v["failed"])),
        ("REFUSED 0", v["refused"] == 0, str(v["refused"])),
        ("건너뛴 행 시트별 차이 0", v["skipped_diff_sheets"] == 0, "%d == 0" % v["skipped_diff_sheets"]),
    ]
    return rows


def print_verdict(rows):
    for name, ok, text in rows:
        print("%s\t%s\t%s" % ("PASS" if ok else "FAIL", name, text))
    return all(ok for _, ok, _ in rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--file", required=True)
    ap.add_argument("--game", required=True)
    ap.add_argument("--pid", type=int)
    ap.add_argument("--connector", action="store_true")
    ap.add_argument("--reviewer")
    a = ap.parse_args()
    if a.connector and not a.reviewer:
        ap.error("--connector에는 --reviewer가 필요하다")
    base = a.base.rstrip("/")
    pid = a.pid or find_pid(urlparse(base).port or 80)
    sampler = HeapSampler(pid) if pid else None
    if sampler:
        sampler.start()
    else:
        print("# 경고: 백엔드 pid를 못 찾아 힙을 재지 않음")

    rows = []  # (단계, 값)

    def rec(name, val):
        rows.append((name, val))
        print("%s\t%s" % (name, val if isinstance(val, str) else "%.2f" % val), flush=True)

    # 1. 올리기
    t0 = time.perf_counter()
    with open(a.file, "rb") as f:
        r = requests.post(base + "/api/projects", files={"file": ("measure.xlsx", f)}, data={"gameName": a.game}, timeout=600)
    r.raise_for_status()
    proj = r.json()
    pid_proj, game_id = proj["id"], proj["gameId"]
    rec("upload", time.perf_counter() - t0)
    rec("sheets_in_project", str(proj["sheetCount"]))
    rec("rows_in_project", str(proj["rowCount"]))

    if a.connector:
        return connector_main(a, base, sampler, rec, pid_proj, game_id)

    # 2. 헤더 찾기
    wb = openpyxl.load_workbook(a.file, read_only=True, data_only=True)
    orig_headers = {}
    plan = []
    skipped = []
    for i, ws in enumerate(wb.worksheets, start=1):
        found = find_header(ws)
        if not found:
            skipped.append(i)
            continue
        plan.append((ws.title, found))
    wb.close()
    print("# 헤더 못 찾아 건너뜀: %d개 (시트 번호 %s)" % (len(skipped), skipped))

    # 3. 정규화
    per = []
    normalized_ok = 0
    failed = []
    t_all = time.perf_counter()
    for title, (hr, src, ko) in plan:
        body = {"sheetName": title, "headerRow": hr,
                "pairs": [{"srcCol": src, "koColMode": "EXISTING_TO_NEW", "koCol": ko, "koHeader": "KR_Fix"}],
                "retranslateExisting": False}
        t0 = time.perf_counter()
        resp = requests.put("%s/api/projects/%d/normalize" % (base, pid_proj), json=body, timeout=600)
        dt = time.perf_counter() - t0
        per.append(dt)
        if resp.ok:
            normalized_ok += 1
        else:
            failed.append((len(per), resp.status_code))
    total = time.perf_counter() - t_all
    rec("normalize_sheets_ok", str(normalized_ok))
    rec("normalize_sheets_skipped_by_header_detector", str(len(skipped)))
    rec("normalize_sheets_failed", str(len(failed)))
    if failed:
        print("# 정규화 실패(순번,상태): %s" % failed)
    rec("normalize_total", total)
    rec("normalize_longest_sheet", max(per) if per else 0.0)
    if total > 60:
        acc, cnt = 0.0, 0
        for dt in per:
            acc += dt
            if acc > 60:
                break
            cnt += 1
        rec("normalize_sheets_within_60s", str(cnt))
    heap_after_norm = sampler.sample() if sampler else None

    # 4. 읽기·내보내기
    rec("review_first_page", median_time(lambda: requests.get(
        "%s/api/games/%d/review/rows?page=0&size=50" % (base, game_id), timeout=300).raise_for_status()))
    rec("review_summary", median_time(lambda: requests.get(
        "%s/api/games/%d/review/rows/summary" % (base, game_id), timeout=300).raise_for_status()))
    rec("export_preview", median_time(lambda: requests.get(
        "%s/api/projects/%d/export/preview" % (base, pid_proj), timeout=300).raise_for_status()))
    opts = {"addFlagColumn": False, "addHistorySheet": False, "addGlossarySheet": False, "overwriteKo": True, "addNoteColumn": False}
    last = {}

    def do_export():
        rr = requests.post("%s/api/projects/%d/export" % (base, pid_proj), json=opts, timeout=600)
        rr.raise_for_status()
        last["bytes"] = rr.content

    rec("export", median_time(do_export))
    heap_after_export = sampler.sample() if sampler else None

    # 5. 원본 대조
    orig = openpyxl.load_workbook(a.file, read_only=True, data_only=True)
    new = openpyxl.load_workbook(io.BytesIO(last["bytes"]), read_only=True, data_only=True)
    o_names, n_names = orig.sheetnames, new.sheetnames
    same_count = len(o_names) == len(n_names)
    same_order = o_names == n_names
    bad_hdr = []
    n_detected = n_fallback = n_empty_ref = 0
    detected_rows = dict((t, f[0]) for t, f in plan)
    for i, name in enumerate(o_names, start=1):
        if name not in n_names:
            bad_hdr.append(i)
            continue
        # 시트마다 헤더 행(찾은 시트는 그 행, 못 찾은 시트는 1행)을 앞부분 일치로 비교한다
        hr = detected_rows.get(name, 1)
        if name in detected_rows:
            n_detected += 1
        else:
            n_fallback += 1
        orow = next(orig[name].iter_rows(min_row=hr, max_row=hr, values_only=True), ())
        nrow = next(new[name].iter_rows(min_row=hr, max_row=hr, values_only=True), ())
        n = len(orow)
        # 끝의 빈 칸은 무시
        while n and orow[n - 1] in (None, ""):
            n -= 1
        if n == 0:
            n_empty_ref += 1  # 원본 헤더 행이 비어 있어 비교할 것이 없는 시트
        if tuple(nrow[:n]) != tuple(orow[:n]):
            bad_hdr.append(i)
    orig.close()
    new.close()
    rec("compare_sheet_count_same", str(same_count))
    rec("compare_sheet_order_same", str(same_order))
    rec("compare_header_rows_differing_sheets", str(len(bad_hdr)))
    rec("compare_sheets_with_detected_header_row", str(n_detected))
    rec("compare_sheets_with_row1_fallback", str(n_fallback))
    rec("compare_sheets_with_empty_reference_row", str(n_empty_ref))
    if bad_hdr:
        print("# 헤더 행이 다른 시트 번호: %s" % bad_hdr)

    # 6. 힙
    if sampler:
        sampler.stop_ev.set()
        rec("heap_after_normalize_used", mb(heap_after_norm[0]) if heap_after_norm else "?")
        rec("heap_after_export_used", mb(heap_after_export[0]) if heap_after_export else "?")
        rec("heap_peak_used_sampled", mb(sampler.peak_used))
        rec("heap_peak_committed_sampled", mb(sampler.peak_committed))
        rec("heap_samples_ok", str(sampler.ok))
        rec("heap_samples_failed_or_empty", str(sampler.failed))

    # 문턱
    print("\n# 문턱 확인")
    vals = dict(rows)
    chk = [("review_first_page", THRESHOLD["review_first"]), ("review_summary", THRESHOLD["summary"]),
           ("export_preview", THRESHOLD["export_preview"]), ("export", THRESHOLD["export"])]
    over = []
    for k, lim in chk:
        ok = vals[k] <= lim
        print("%s\t%.2f\t<= %.1f\t%s" % (k, vals[k], lim, "통과" if ok else "초과"))
        if not ok:
            over.append(k)
    if sampler:
        gb = sampler.peak_used / 1073741824.0
        ok = gb <= THRESHOLD["heap_gb"]
        print("heap_peak_used\t%.2f GB\t<= %.1f GB\t%s" % (gb, THRESHOLD["heap_gb"], "통과" if ok else "초과"))
        if not ok:
            over.append("heap")
    print("# 초과: %s" % (over or "없음"))
    print("# project_id=%d game_id=%d" % (pid_proj, game_id))
    return 0


if __name__ == "__main__":
    sys.exit(main())
