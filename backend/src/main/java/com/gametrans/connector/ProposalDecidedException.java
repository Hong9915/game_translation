package com.gametrans.connector;

import com.gametrans.connector.ConnectorDtos.ProposalDetail;

/** 이미 저장·넣지 않음 처리됐거나 만료된 제안에 apply·dismiss가 왔다. 컨트롤러가 409로 바꾸고 지금 상태(proposal)를 함께 돌려준다. */
public class ProposalDecidedException extends RuntimeException {
    private final transient ProposalDetail proposal;

    public ProposalDecidedException(String message, ProposalDetail proposal) {
        super(message);
        this.proposal = proposal;
    }

    public ProposalDetail getProposal() { return proposal; }
}
