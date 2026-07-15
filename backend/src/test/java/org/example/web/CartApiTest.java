package org.example.web;

import com.jayway.jsonpath.JsonPath;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class CartApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Test
    void allCartEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/cart/items/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/cart/items/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesProductIdAndQuantityBounds() throws Exception {
        String token = login("13800000301");

        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":0,\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":100000}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/cart/items/1")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/cart/items/1")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":100000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void supportsAbsoluteUpsertListUpdateAndDelete() throws Exception {
        String token = login("13800000301");

        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.sku").value("MASK-50"))
                .andExpect(jsonPath("$.currentPrice").value(12.80));

        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3));

        mockMvc.perform(get("/api/cart")
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3));

        mockMvc.perform(put("/api/cart/items/1")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(4));

        mockMvc.perform(delete("/api/cart/items/1")
                        .header("X-Session-Token", token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/cart")
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void listsExistingItemAsUnavailableAfterProductGoesOffSale() throws Exception {
        String token = login("13800000301");
        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isOk());
        ProductEntity product = productMapper.selectById(1L);
        product.setStatus("OFF_SHELF");
        productMapper.updateById(product);

        mockMvc.perform(get("/api/cart")
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    @Test
    void distinguishesMissingAndOffShelfProductsWhenAdding() throws Exception {
        String token = login("13800000301");

        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9999,\"quantity\":1}"))
                .andExpect(status().isNotFound());

        ProductEntity product = productMapper.selectById(1L);
        product.setStatus("OFF_SHELF");
        productMapper.updateById(product);
        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void isolatesListAndDeleteBetweenUsers() throws Exception {
        String firstToken = login("13800000301");
        String secondToken = login("13800000302");
        add(firstToken, 2L);
        add(secondToken, 4L);

        mockMvc.perform(get("/api/cart").header("X-Session-Token", firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        mockMvc.perform(delete("/api/cart/items/1").header("X-Session-Token", firstToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/cart").header("X-Session-Token", secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(4));
    }

    private void add(String token, long quantity) throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":%d}".formatted(quantity)))
                .andExpect(status().isOk());
    }

    private String login(String phone) throws Exception {
        String loginJson = mockMvc.perform(post("/api/auth/sms/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"123456\"}".formatted(phone)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.read(loginJson, "$.user.phone").toString()).isEqualTo(phone);
        return JsonPath.read(loginJson, "$.token");
    }
}
