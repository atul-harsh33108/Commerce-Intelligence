package com.ecommerce.catalog.integration;

import com.ecommerce.catalog.TestcontainersConfig;
import com.ecommerce.catalog.entity.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The walking skeleton, proven end to end: HTTP → controller → service → JPA → a real PostgreSQL.
 *
 * <p>Not a mocked repository. The point of this test is the parts a mock cannot fail: that the
 * Flyway migration and the entity agree (Hibernate runs as {@code validate} and refuses to start if
 * they do not), that the unique constraint exists, and that the browse query — whose
 * {@code :param IS NULL} form Postgres has to infer types for — actually executes on Postgres rather
 * than only on an in-memory database that is more forgiving.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ProductApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired ObjectMapper json;

    /**
     * Must match {@code app.jwt.*} in application-test.yml.
     *
     * <p>Minted here rather than imported from user-service, because importing it would mean a
     * shared jar (D2) — and because a test that mints its own token proves the thing that matters:
     * this service verifies a signature, and has no idea who produced it.
     */
    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(
            "test-only-signing-key-not-used-anywhere-else-0123456789".getBytes(StandardCharsets.UTF_8));

    private static String tokenFor(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .issuer("ecommerce")
                .claim("role", role)
                .claim("uid", 1L)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(10))))
                .signWith(TEST_KEY)
                .compact();
    }

    @BeforeEach
    void seed() {
        products.deleteAll();
        products.saveAll(List.of(
                Product.builder().productKey("aaa111").name("health_beauty #aaa111")
                        .categoryKey("beleza_saude").category("health_beauty")
                        .price(new BigDecimal("59.90")).weightGrams(300).photosQty(2).active(true).build(),
                Product.builder().productKey("bbb222").name("computers_accessories #bbb222")
                        .categoryKey("informatica_acessorios").category("computers_accessories")
                        .price(new BigDecimal("129.00")).weightGrams(800).photosQty(1).active(true).build(),
                Product.builder().productKey("ccc333").name("health_beauty #ccc333")
                        .categoryKey("beleza_saude").category("health_beauty")
                        .price(new BigDecimal("19.90")).weightGrams(100).photosQty(3).active(true).build()));
    }

    // ---------------------------------------------------------------------------------------------
    // The Definition of Done for Step 1.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/products/{id} returns the row that is actually in PostgreSQL")
    void getByIdReturnsPersistedRow() throws Exception {
        Long id = products.findByProductKey("aaa111").orElseThrow().getId();

        mvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productKey").value("aaa111"))
                .andExpect(jsonPath("$.category").value("health_beauty"))
                // 59.90, not 59.9: the column is numeric(12,2) and BigDecimal keeps the scale. A
                // double would have serialised 59.9 here, which is the first visible symptom of
                // money-in-floating-point.
                .andExpect(jsonPath("$.price").value(59.90));
    }

    @Test
    @DisplayName("GET /api/products/{id} for a missing row is 404 in the standard envelope")
    void missingProductIs404() throws Exception {
        mvc.perform(get("/api/products/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/products/999999"));
    }

    // ---------------------------------------------------------------------------------------------
    // The query whose type inference only fails on a real Postgres.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("browse with no filters pages over everything")
    void browseUnfiltered() throws Exception {
        mvc.perform(get("/api/products").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("browse filters by category and by max price, together")
    void browseFiltered() throws Exception {
        mvc.perform(get("/api/products")
                        .param("category", "health_beauty")
                        .param("maxPrice", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].productKey").value("ccc333"));
    }

    @Test
    @DisplayName("categories come back as the distinct sorted vocabulary")
    void categories() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("computers_accessories"))
                .andExpect(jsonPath("$[1]").value("health_beauty"));
    }

    @Test
    @DisplayName("bulk lookup resolves the keys it can and silently omits the ones it cannot")
    void bulkLookupOmitsUnknownKeys() throws Exception {
        mvc.perform(post("/api/products/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("productKeys", List.of("aaa111", "nope", "bbb222")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ---------------------------------------------------------------------------------------------
    // Authorization. The gateway does this one hop earlier; this proves the service does not rely on
    // it.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("reads are public; a write with no token is 401")
    void writeWithoutTokenIs401() throws Exception {
        mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKey":"zzz999","name":"x","category":"toys","price":1.00}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("a valid USER token is still 403 on a write: authenticated is not authorized")
    void writeAsUserIs403() throws Exception {
        mvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + tokenFor("shopper", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKey":"zzz999","name":"x","category":"toys","price":1.00}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("a forged X-Auth-Role header grants nothing: the signature is what is checked")
    void forgedIdentityHeaderIsIgnored() throws Exception {
        mvc.perform(post("/api/products")
                        .header("X-Auth-Role", "ADMIN")
                        .header("X-Auth-User", "attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKey":"zzz999","name":"x","category":"toys","price":1.00}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN creates, and the row lands in PostgreSQL with a Location header")
    void adminCanCreate() throws Exception {
        mvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + tokenFor("admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKey":"zzz999","name":"toys #zzz999","category":"toys","price":9.99}"""))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("Location"))
                .andExpect(jsonPath("$.productKey").value("zzz999"));

        assertThat(products.findByProductKey("zzz999")).isPresent();
    }

    @Test
    @DisplayName("a duplicate product key is 409, enforced by the database and not by a pre-check")
    void duplicateKeyIs409() throws Exception {
        mvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + tokenFor("admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKey":"aaa111","name":"dupe","category":"toys","price":9.99}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("validation failures name the offending fields")
    void validationNamesFields() throws Exception {
        mvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + tokenFor("admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productKey":"","name":"","category":"toys","price":-1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.productKey").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.price").exists());
    }

    @Test
    @DisplayName("DELETE deactivates rather than removing, so history stays joinable")
    void deleteIsSoft() throws Exception {
        Long id = products.findByProductKey("bbb222").orElseThrow().getId();

        mvc.perform(delete("/api/products/{id}", id)
                        .header("Authorization", "Bearer " + tokenFor("admin", "ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(products.findById(id)).isPresent()
                .get().extracting(Product::isActive).isEqualTo(false);
        // ...and it disappears from browse, which is the user-visible half of "soft".
        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }
}
