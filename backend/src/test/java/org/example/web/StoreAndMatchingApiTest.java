package org.example.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false",
        "app.matching.enabled=false"
})
@AutoConfigureMockMvc
class StoreAndMatchingApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesTheSingleStoreAndKeepsLegacyMatchingRoutesDisabled() throws Exception {
        String store = mockMvc.perform(get("/api/store"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.read(store, "$.id").toString()).isEqualTo("1");
        assertThat(JsonPath.read(store, "$.businessStatus").toString()).isEqualTo("OPEN");
        assertThat(JsonPath.read(store, "$.storeName").toString()).isNotBlank();

        mockMvc.perform(get("/api/orders")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/trades")).andExpect(status().isNotFound());
    }
}
