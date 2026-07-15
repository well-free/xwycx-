package org.example.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wechat_schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
class WechatSchemaIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateWechatTablesAndOrderPaymentColumns() throws Exception {
        assertThat(tableExists("wechat_identities")).isTrue();
        assertThat(tableExists("cart_items")).isTrue();
        assertThat(columnExists("cart_items", "version")).isTrue();
        assertThat(columnDefault("cart_items", "version")).isEqualTo("0");
        assertThat(columnExists("customer_orders", "shipping_snapshot")).isTrue();
        assertThat(columnExists("payment_orders", "prepay_id")).isTrue();
        assertThat(columnExists("user_sessions", "wechat_identity_id")).isTrue();
        assertThat(tableExists("store_settings")).isTrue();
        assertThat(tableExists("outbox_events")).isTrue();
        assertThat(columnExists("products", "reserved_stock")).isTrue();
        assertThat(columnExists("products", "sold_stock")).isTrue();
        assertThat(columnExists("inventory_logs", "business_key")).isTrue();
    }

    private boolean tableExists(String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet tables = metadata.getTables(null, null, tableName, new String[]{"TABLE"})) {
                return tables.next();
            }
        }
    }

    private boolean columnExists(String tableName, String columnName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(null, null, tableName, columnName)) {
                return columns.next();
            }
        }
    }

    private String columnDefault(String tableName, String columnName) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(null, null, tableName, columnName)) {
                assertThat(columns.next()).isTrue();
                return columns.getString("COLUMN_DEF");
            }
        }
    }
}
