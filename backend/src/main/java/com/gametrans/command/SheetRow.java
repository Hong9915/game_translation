package com.gametrans.command;

/** 작업 목록에 넣는 시트 행. 확정 행은 같은 원문 통일의 목표를 정할 때만 읽는다. josaChecked가 참이면 josaHint가 지금 번역의 검사 결과다. */
public record SheetRow(Long id, Integer rowNo, String src, String ko, boolean confirmed, Boolean josaChecked, String josaHint, boolean josaIgnored) {}
