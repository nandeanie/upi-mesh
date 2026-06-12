package com.demo.upimesh;

import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST endpoint integration tests using MockMvc / @WebMvcTest.
 *
 * Covers:
 *   1.  GET  /api/health             — health probe returns UP
 *   2.  GET  /api/server-key         — returns RSA-2048 public key
 *   3.  GET  /api/accounts           — returns seeded demo accounts
 *   4.  GET  /api/transactions        — returns list (may be empty)
 *   5.  GET  /api/audit              — returns summary + list
 *   6.  POST /api/bridge/ingest      — 401 without API key
 *   7.  POST /api/bridge/ingest      — valid packet with API key
 *   8.  POST /api/demo/reset-full    — 401 without API key
 *   9.  POST /api/demo/reset-full    — 200 with API key
 *   10. POST /api/demo/send          — validation: missing required fields
 *   11. GET  /api/mesh/state         — mesh state structure
 *   12. POST /api/demo/send          — full send returns packetId
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyService idempotency;

    @Value("${bridge.api-key:demo-key}")
    private String apiKey;

    @BeforeEach
    void reset() {
        idempotency.clear();
    }

    // ── 1. Health ────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /api/health — returns 200 with status=UP")
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("upi-offline-mesh"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── 2. Server key ────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("GET /api/server-key — returns RSA public key metadata")
    void serverKeyReturnsMetadata() throws Exception {
        mockMvc.perform(get("/api/server-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").exists())
                .andExpect(jsonPath("$.algorithm").value("RSA-2048"))
                .andExpect(jsonPath("$.hybridScheme").value(
                        containsString("RSA-OAEP-SHA256")));
    }

    // ── 3. Accounts ──────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /api/accounts — returns 4 seeded demo accounts")
    void accountsReturnsSeedData() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[*].vpa", hasItem("alice@demo")))
                .andExpect(jsonPath("$[*].vpa", hasItem("bob@demo")));
    }

    // ── 4. Transactions ──────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /api/transactions — returns JSON array")
    void transactionsReturnsArray() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── 5. Audit ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("GET /api/audit — returns summary with expected fields")
    void auditReturnsSummary() throws Exception {
        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalSettled").exists())
                .andExpect(jsonPath("$.summary.totalRejected").exists())
                .andExpect(jsonPath("$.summary.totalInvalid").exists())
                .andExpect(jsonPath("$.summary.idempotencyCacheSize").exists())
                .andExpect(jsonPath("$.recentTransactions").isArray());
    }

    // ── 6. Bridge/ingest — 401 without key ──────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /api/bridge/ingest — 401 when Authorization header is missing")
    void ingestRequiresApiKey() throws Exception {
        mockMvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packetId\":\"test\",\"ttl\":5,\"createdAt\":0,\"ciphertext\":\"abc\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── 7. Bridge/ingest — valid with key ────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("POST /api/bridge/ingest — INVALID outcome for bogus ciphertext with valid key")
    void ingestWithKeyReturnsInvalidForBogusCiphertext() throws Exception {
        String fakePacket = """
            {
              "packetId": "test-packet-001",
              "ttl": 3,
              "createdAt": %d,
              "ciphertext": "bm90LXJlYWwtY2lwaGVydGV4dA=="
            }
            """.formatted(System.currentTimeMillis());

        mockMvc.perform(post("/api/bridge/ingest")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("X-Bridge-Node-Id", "test-bridge")
                        .header("X-Hop-Count", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fakePacket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INVALID"));
    }

    // ── 8. Reset-full — 401 without key ─────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("POST /api/demo/reset-full — 401 when Authorization header is missing")
    void resetFullRequiresApiKey() throws Exception {
        mockMvc.perform(post("/api/demo/reset-full"))
                .andExpect(status().isUnauthorized());
    }

    // ── 9. Reset-full — 200 with key ─────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("POST /api/demo/reset-full — 200 with valid API key")
    void resetFullSucceedsWithKey() throws Exception {
        mockMvc.perform(post("/api/demo/reset-full")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(
                        containsString("full reset complete")));
    }

    // ── 10. Demo/send — validation ───────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /api/demo/send — 400 when required fields are missing")
    void demoSendValidatesRequiredFields() throws Exception {
        mockMvc.perform(post("/api/demo/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 11. Mesh state ────────────────────────────────────────────────────────

    @Test
    @Order(11)
    @DisplayName("GET /api/mesh/state — returns devices array and cache size")
    void meshStateReturnsDevices() throws Exception {
        mockMvc.perform(get("/api/mesh/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices").isArray())
                .andExpect(jsonPath("$.idempotencyCacheSize").isNumber());
    }

    // ── 12. Full demo send ────────────────────────────────────────────────────

    @Test
    @Order(12)
    @DisplayName("POST /api/demo/send — returns packetId and ciphertext preview")
    void demoSendCreatesPacket() throws Exception {
        String body = """
            {
              "senderVpa":   "alice@demo",
              "receiverVpa": "bob@demo",
              "amount":      50.00,
              "pin":         "1234"
            }
            """;

        mockMvc.perform(post("/api/demo/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packetId").exists())
                .andExpect(jsonPath("$.ciphertextPreview").exists())
                .andExpect(jsonPath("$.ttl").value(5));
    }
}
