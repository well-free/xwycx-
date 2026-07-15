package org.example.web;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@AutoConfigureMockMvc
class AddressApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from shipping_addresses where receiver_phone like '138000005%'");
        jdbcTemplate.update("delete from user_sessions where user_id in "
                + "(select id from users where phone like '138000005%')");
        jdbcTemplate.update("delete from users where phone like '138000005%'");
    }

    @Test
    void allAddressEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/addresses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Receiver", false)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/addresses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Receiver", false)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/addresses/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesEveryAddressField() throws Exception {
        String token = login("13800000501");

        assertBadRequest(token, request("", "13800000501", "Zhejiang", "Hangzhou", "Xihu", "Road"));
        assertBadRequest(token, request("N".repeat(65), "13800000501", "Zhejiang", "Hangzhou", "Xihu", "Road"));
        assertBadRequest(token, request("Receiver", "12345", "Zhejiang", "Hangzhou", "Xihu", "Road"));
        assertBadRequest(token, request("Receiver", "10000000000", "Zhejiang", "Hangzhou", "Xihu", "Road"));
        assertBadRequest(token, requestWithoutPhone());
        assertBadRequest(token, request("Receiver", "13800000501", "", "Hangzhou", "Xihu", "Road"));
        assertBadRequest(token, request("Receiver", "13800000501", "P".repeat(65), "Hangzhou", "Xihu", "Road"));
        assertBadRequest(token, request("Receiver", "13800000501", "Zhejiang", "", "Xihu", "Road"));
        assertBadRequest(token, request("Receiver", "13800000501", "Zhejiang", "C".repeat(65), "Xihu", "Road"));
        assertBadRequest(token, request("Receiver", "13800000501", "Zhejiang", "Hangzhou", "", "Road"));
        assertBadRequest(token, request("Receiver", "13800000501", "Zhejiang", "Hangzhou", "D".repeat(65), "Road"));
        assertBadRequest(token, request("Receiver", "13800000501", "Zhejiang", "Hangzhou", "Xihu", ""));
        assertBadRequest(token, request("Receiver", "13800000501", "Zhejiang", "Hangzhou", "Xihu", "R".repeat(501)));
    }

    @Test
    void createsListsUpdatesAndDeletesWithExactResponseShape() throws Exception {
        String token = login("13800000502");

        String createJson = mockMvc.perform(post("/api/addresses")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Receiver", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAddress").value(true))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> created = objectMapper.readValue(createJson, new TypeReference<>() { });
        assertThat(created.keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
                "id", "userId", "receiverName", "receiverPhone", "province", "city", "district",
                "detail", "defaultAddress", "createdAt", "updatedAt"));
        long addressId = ((Number) created.get("id")).longValue();

        mockMvc.perform(get("/api/addresses").header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").value(addressId))
                .andExpect(jsonPath("$.items[0].receiverName").value("Receiver"));

        mockMvc.perform(put("/api/addresses/{id}", addressId)
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Updated", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(addressId))
                .andExpect(jsonPath("$.receiverName").value("Updated"))
                .andExpect(jsonPath("$.defaultAddress").value(true));

        mockMvc.perform(delete("/api/addresses/{id}", addressId)
                        .header("X-Session-Token", token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/addresses").header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void acceptsDetailAtMaximumLength() throws Exception {
        String token = login("13800000503");
        String detail = "R".repeat(500);

        mockMvc.perform(post("/api/addresses")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Receiver", "13800000503", "Zhejiang", "Hangzhou", "Xihu",
                                detail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value(detail));
    }

    @Test
    void anotherUsersAddressIsNotFoundForUpdateAndDelete() throws Exception {
        String ownerToken = login("13800000504");
        String otherToken = login("13800000505");
        String created = mockMvc.perform(post("/api/addresses")
                        .header("X-Session-Token", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Private", false)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long addressId = ((Number) JsonPath.read(created, "$.id")).longValue();

        mockMvc.perform(put("/api/addresses/{id}", addressId)
                        .header("X-Session-Token", otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Stolen", true)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/addresses/{id}", addressId)
                        .header("X-Session-Token", otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/addresses").header("X-Session-Token", otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(get("/api/addresses").header("X-Session-Token", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].receiverName").value("Private"));
    }

    private void assertBadRequest(String token, String content) throws Exception {
        mockMvc.perform(post("/api/addresses")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest());
    }

    private String validRequest(String receiverName, boolean defaultAddress) {
        return """
                {
                  "receiverName": "%s",
                  "receiverPhone": "13800000501",
                  "province": "Zhejiang",
                  "city": "Hangzhou",
                  "district": "Xihu",
                  "detail": "No. 1 Road",
                  "defaultAddress": %s
                }
                """.formatted(receiverName, defaultAddress);
    }

    private String request(String name, String phone, String province, String city, String district, String detail) {
        return """
                {
                  "receiverName": "%s",
                  "receiverPhone": "%s",
                  "province": "%s",
                  "city": "%s",
                  "district": "%s",
                  "detail": "%s",
                  "defaultAddress": false
                }
                """.formatted(name, phone, province, city, district, detail);
    }

    private String requestWithoutPhone() {
        return """
                {
                  "receiverName": "Receiver",
                  "province": "Zhejiang",
                  "city": "Hangzhou",
                  "district": "Xihu",
                  "detail": "Road",
                  "defaultAddress": false
                }
                """;
    }

    private String login(String phone) throws Exception {
        String loginJson = mockMvc.perform(post("/api/auth/sms/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"123456\"}".formatted(phone)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginJson, "$.token");
    }
}
