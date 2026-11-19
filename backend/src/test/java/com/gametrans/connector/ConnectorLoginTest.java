package com.gametrans.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 커넥터 계정 로그인: 비밀번호·막힘·검수자 연결을 한 문구로만 거절한다. */
class ConnectorLoginTest extends IntegrationTest {
    static final String PW = "pw-1234";
    static final String WRONG = "아이디 또는 비밀번호가 틀렸습니다.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long reviewer() throws Exception {
        String body = mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"커넥터검수자-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.id", Long.class);
    }

    String account(Long reviewerId) {
        String login = "acc-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into connector_account(login_id,password_hash,reviewer_id) values (?, crypt(?, gen_salt('bf')), ?)",
                login, PW, reviewerId);
        return login;
    }

    ResultActions login(String id, String pw) throws Exception {
        return mvc.perform(post("/api/connector/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"" + pw + "\"}"));
    }

    long accountId(String login) {
        return jdbc.queryForObject("select id from connector_account where login_id = ?", Long.class, login);
    }

    @Test
    void loginReturnsAccountAndReviewer() throws Exception {
        long rid = reviewer();
        String login = account(rid);
        login(login, PW).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value((int) accountId(login)))
                .andExpect(jsonPath("$.reviewer.id").value((int) rid))
                .andExpect(jsonPath("$.disabled").value(false));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        String login = account(reviewer());
        login(login, "nope").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
    }

    @Test
    void loginRejectsUnknownId() throws Exception {
        login("없는-계정", PW).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
    }

    @Test
    void loginRejectsDisabledAccount() throws Exception {
        String login = account(reviewer());
        jdbc.update("update connector_account set disabled = true where login_id = ?", login);
        login(login, PW).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
    }

    @Test
    void loginRejectsAccountWithoutReviewer() throws Exception {
        String login = account(null);
        login(login, PW).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
    }

    @Test
    void accountShowsDisabledAndReviewer() throws Exception {
        long rid = reviewer();
        String login = account(rid);
        long id = accountId(login);
        mvc.perform(get("/api/connector/accounts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value((int) id))
                .andExpect(jsonPath("$.disabled").value(false))
                .andExpect(jsonPath("$.reviewer.id").value((int) rid));
        jdbc.update("update connector_account set disabled = true where id = ?", id);
        mvc.perform(get("/api/connector/accounts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(true));

        long noReviewer = accountId(account(null));
        mvc.perform(get("/api/connector/accounts/{id}", noReviewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewer").doesNotExist());
    }

    @Test
    void accountUnknownIs404() throws Exception {
        mvc.perform(get("/api/connector/accounts/{id}", 999999999L)).andExpect(status().isNotFound());
    }
}
