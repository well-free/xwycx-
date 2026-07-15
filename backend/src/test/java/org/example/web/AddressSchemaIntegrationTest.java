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
        "spring.datasource.url=jdbc:h2:mem:address_schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
class AddressSchemaIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void migrationExpandsShippingAddressDetailToFiveHundredCharacters() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(
                    null, null, "shipping_addresses", "detail")) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(500);
            }
        }
    }
}
