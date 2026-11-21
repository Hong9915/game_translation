package com.gametrans.normalize;

/** 다시 정규화하려는 시트에 검수 흔적이 있다(커넥터 경로의 트랜잭션 안 재검사). 커넥터는 이것을 REFUSED로 돌려준다. */
public class SheetHasWorkException extends RuntimeException {
    public SheetHasWorkException(String sheetName) {
        super("검수 흔적이 있는 시트입니다: " + sheetName);
    }
}
