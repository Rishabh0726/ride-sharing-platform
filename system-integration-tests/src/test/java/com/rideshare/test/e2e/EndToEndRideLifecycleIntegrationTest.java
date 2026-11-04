package com.rideshare.test.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.test.e2e.harness.SystemIntegrationCluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EndToEndRideLifecycleIntegrationTest {

    private static SystemIntegrationCluster cluster;
    private static WebTestClient client;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String driverToken;
    private static Long driverUserId;
    private static String passengerToken;
    private static Long passengerUserId;

    private static String quoteToken;
    private static Long rideId;
    private static Long driverNotificationId;

    @BeforeAll
    static void setUpAll() {
        cluster = new SystemIntegrationCluster();
        cluster.start();
        client = cluster.getWebTestClient();
    }

    @AfterAll
    static void tearDownAll() {
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Step 1: Register Passenger Alice through API Gateway (deliberately before driver to differentiate user ID from driver profile ID)")
    void testStep1_RegisterPassenger() throws Exception {
        String passengerRegisterJson = """
                {
                    "email": "passenger.alice@rideshare.com",
                    "password": "SecurePassword123!",
                    "firstName": "Alice",
                    "lastName": "Smith",
                    "phoneNumber": "+919876543202",
                    "role": "PASSENGER"
                }
                """;

        EntityExchangeResult<byte[]> result = client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(passengerRegisterJson)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.email").isEqualTo("passenger.alice@rideshare.com")
                .jsonPath("$.role").isEqualTo("ROLE_PASSENGER")
                .jsonPath("$.accessToken").isNotEmpty()
                .returnResult();

        JsonNode root = mapper.readTree(result.getResponseBody());
        passengerToken = root.path("accessToken").asText();
        passengerUserId = root.path("userId").asLong();
        assertThat(passengerUserId).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("Step 2: Register Driver Bob through API Gateway (users.id > driver_profiles.id)")
    void testStep2_RegisterDriver() throws Exception {
        String driverRegisterJson = """
                {
                    "email": "driver.bob@rideshare.com",
                    "password": "SecurePassword123!",
                    "firstName": "Bob",
                    "lastName": "Jones",
                    "phoneNumber": "+919876543201",
                    "role": "DRIVER",
                    "vehicleModel": "Honda City",
                    "licensePlate": "KA-01-AB-1234"
                }
                """;

        EntityExchangeResult<byte[]> result = client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(driverRegisterJson)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.email").isEqualTo("driver.bob@rideshare.com")
                .jsonPath("$.role").isEqualTo("ROLE_DRIVER")
                .jsonPath("$.accessToken").isNotEmpty()
                .returnResult();

        JsonNode root = mapper.readTree(result.getResponseBody());
        driverToken = root.path("accessToken").asText();
        driverUserId = root.path("userId").asLong();
        assertThat(driverUserId).isNotNull();
        // Crucial regression invariant: driverUserId must not equal 1 (Alice is 1, Bob is 2)
        assertThat(driverUserId).isNotEqualTo(passengerUserId);
    }

    @Test
    @Order(3)
    @DisplayName("Step 3: Driver Bob toggles availability to ONLINE with coordinates (verifies driverId == users.id)")
    void testStep3_DriverToggleAvailability() {
        String availabilityJson = """
                {
                    "online": true,
                    "latitude": 12.9716,
                    "longitude": 77.5946
                }
                """;

        client.patch()
                .uri("/api/v1/drivers/availability")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(availabilityJson)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("AVAILABLE")
                .jsonPath("$.online").isEqualTo(true)
                .jsonPath("$.driverId").isEqualTo(driverUserId);
    }

    @Test
    @Order(4)
    @DisplayName("Step 4: Passenger Alice discovers available drivers nearby (verifies driverId == users.id)")
    void testStep4_PassengerDiscoverDrivers() {
        client.get()
                .uri("/api/v1/drivers/available?latitude=12.9716&longitude=77.5946&radiusKm=5.0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].vehicleModel").isEqualTo("Honda City")
                .jsonPath("$[0].licensePlate").isEqualTo("KA-01-AB-1234")
                .jsonPath("$[0].driverId").isEqualTo(driverUserId);
    }

    @Test
    @Order(5)
    @DisplayName("Step 5: Passenger Alice requests fare estimation quote")
    void testStep5_PassengerEstimateFare() throws Exception {
        String estimateJson = """
                {
                    "pickupLatitude": 12.9716,
                    "pickupLongitude": 77.5946,
                    "destinationLatitude": 12.9352,
                    "destinationLongitude": 77.6245,
                    "vehicleType": "SEDAN"
                }
                """;

        EntityExchangeResult<byte[]> result = client.post()
                .uri("/api/v1/pricing/estimate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(estimateJson)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.distanceKm").isEqualTo(5.18)
                .jsonPath("$.estimatedFare").isEqualTo(142.77)
                .jsonPath("$.currency").isEqualTo("INR")
                .jsonPath("$.quoteToken").isNotEmpty()
                .returnResult();

        JsonNode root = mapper.readTree(result.getResponseBody());
        quoteToken = root.path("quoteToken").asText();
        assertThat(quoteToken).isNotBlank();
    }

    @Test
    @Order(6)
    @DisplayName("Step 6: Passenger Alice creates ride referencing quote token")
    void testStep6_PassengerCreateRide() throws Exception {
        String createRideJson = String.format("""
                {
                    "fareQuoteToken": "%s",
                    "pickupLocationName": "Cubbon Park",
                    "pickupLatitude": 12.9716,
                    "pickupLongitude": 77.5946,
                    "destinationLocationName": "Koramangala",
                    "destinationLatitude": 12.9352,
                    "destinationLongitude": 77.6245
                }
                """, quoteToken);

        EntityExchangeResult<byte[]> result = client.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRideJson)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("REQUESTED")
                .jsonPath("$.estimatedFare").isEqualTo(142.77)
                .jsonPath("$.passengerId").isEqualTo(passengerUserId)
                .returnResult();

        JsonNode root = mapper.readTree(result.getResponseBody());
        rideId = root.path("id").asLong();
        assertThat(rideId).isPositive();
    }

    @Test
    @Order(7)
    @DisplayName("Step 7: Driver Bob accepts ride via distributed lease protocol")
    void testStep7_DriverAcceptRide() {
        client.post()
                .uri("/api/v1/rides/" + rideId + "/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(rideId)
                .jsonPath("$.status").isEqualTo("ACCEPTED")
                .jsonPath("$.driverId").isEqualTo(driverUserId);
    }

    @Test
    @Order(8)
    @DisplayName("Targeted Security Regression: Oversized chunked request to bodyless /arrive is rejected with 413 and causes ZERO downstream side effects")
    void testOversizedChunkedArrive_RejectedWith413AndNoSideEffects() {
        // Driver Bob sends 2.5 MiB chunked payload to bodyless endpoint /arrive (limit is 2 MiB)
        byte[] chunk = new byte[100 * 1024];
        Flux<DataBuffer> chunkFlux = Flux.range(0, 25)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

        client.post()
                .uri("/api/v1/rides/" + rideId + "/arrive")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromDataBuffers(chunkFlux))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.title").isEqualTo("Payload Too Large");

        // PROOF 1: Verify ride status is UNCHANGED in downstream ride-service (remains ACCEPTED, NOT DRIVER_ARRIVING)
        client.get()
                .uri("/api/v1/rides/" + rideId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ACCEPTED");

        // PROOF 2: Verify zero DRIVER_ARRIVING notification was produced in downstream notification-service
        client.get()
                .uri("/api/v1/notifications?size=10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(2); // Only RIDE_REQUESTED and RIDE_ACCEPTED
    }

    @Test
    @Order(9)
    @DisplayName("Step 8: Driver Bob signals arrival at pickup location")
    void testStep8_DriverArrive() {
        client.post()
                .uri("/api/v1/rides/" + rideId + "/arrive")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DRIVER_ARRIVING");
    }

    @Test
    @Order(10)
    @DisplayName("Step 9: Driver Bob starts ride with passenger aboard")
    void testStep9_DriverStartRide() {
        client.post()
                .uri("/api/v1/rides/" + rideId + "/start")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("IN_PROGRESS");
    }

    @Test
    @Order(11)
    @DisplayName("Step 10: Driver Bob completes ride at destination")
    void testStep10_DriverCompleteRide() {
        client.post()
                .uri("/api/v1/rides/" + rideId + "/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.finalFare").isEqualTo(142.77);
    }

    @Test
    @Order(12)
    @DisplayName("Step 11: Passenger Alice verifies persisted notifications and read tracking")
    void testStep11_PassengerNotifications() {
        // Alice should have 5 notifications: REQUESTED, ASSIGNED, ARRIVING, STARTED, COMPLETED
        client.get()
                .uri("/api/v1/notifications?size=10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(5);

        // Verify unread count is initially 5
        client.get()
                .uri("/api/v1/notifications/unread-count")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unreadCount").isEqualTo(5);

        // Mark all as read
        client.patch()
                .uri("/api/v1/notifications/read-all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.markedCount").isEqualTo(5);

        // Verify unread count is now 0
        client.get()
                .uri("/api/v1/notifications/unread-count")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unreadCount").isEqualTo(0);
    }

    @Test
    @Order(13)
    @DisplayName("Step 12: Driver Bob verifies persisted notifications")
    void testStep12_DriverNotifications() throws Exception {
        // Bob should have 2 notifications: RIDE_ACCEPTED, RIDE_COMPLETED
        EntityExchangeResult<byte[]> result = client.get()
                .uri("/api/v1/notifications?size=10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(2)
                .returnResult();

        JsonNode root = mapper.readTree(result.getResponseBody());
        driverNotificationId = root.path("content").get(0).path("id").asLong();
        assertThat(driverNotificationId).isPositive();
    }

    @Test
    @Order(14)
    @DisplayName("Negative Authorization: Passenger cannot accept a ride (403 Forbidden)")
    void testNegative_PassengerCannotAcceptRide() {
        client.post()
                .uri("/api/v1/rides/" + rideId + "/accept")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @Order(15)
    @DisplayName("Negative Authorization: Driver cannot create a ride request (403 Forbidden)")
    void testNegative_DriverCannotCreateRide() {
        String dummyCreateRide = """
                {
                    "fareQuoteToken": "dummy-token",
                    "pickupLatitude": 12.9716,
                    "pickupLongitude": 77.5946,
                    "destinationLatitude": 12.9352,
                    "destinationLongitude": 77.6245
                }
                """;

        client.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + driverToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dummyCreateRide)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @Order(16)
    @DisplayName("Negative State Conflict: Cannot cancel COMPLETED ride (409 Conflict)")
    void testNegative_CannotCancelCompletedRide() {
        client.post()
                .uri("/api/v1/rides/" + rideId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Changed mind\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @Order(17)
    @DisplayName("Gateway Perimeter Defense: Public call to internal driver reserve blocked with 403 Forbidden")
    void testPerimeter_DirectInternalDriverReserveBlocked() {
        client.post()
                .uri("/api/v1/drivers/" + driverUserId + "/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rideId\":999}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden");
    }

    @Test
    @Order(18)
    @DisplayName("Gateway Perimeter Defense: Public call to internal notification ingestion blocked with 403 Forbidden")
    void testPerimeter_DirectInternalNotificationIngestionBlocked() {
        client.post()
                .uri("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"recipientUserId\":1,\"recipientRole\":\"ROLE_PASSENGER\",\"eventType\":\"RIDE_REQUESTED\",\"title\":\"Test\",\"message\":\"Test\",\"idempotencyKey\":\"k1\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden");
    }

    @Test
    @Order(19)
    @DisplayName("Data Isolation: Passenger Alice cannot mark Driver Bob's notification as read (404 Not Found)")
    void testIsolation_PassengerCannotMarkDriverNotificationRead() {
        client.patch()
                .uri("/api/v1/notifications/" + driverNotificationId + "/read")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + passengerToken)
                .exchange()
                .expectStatus().isNotFound();
    }
}
