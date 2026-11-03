package com.rideshare.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

public final class ProblemDetailWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ProblemDetailWriter() {
    }

    public static Mono<Void> writeProblemResponse(
            ServerWebExchange exchange,
            HttpStatus status,
            String title,
            String detail,
            String errorTypeUri
    ) {
        return writeProblemResponse(exchange, status, title, detail, errorTypeUri, Map.of());
    }

    public static Mono<Void> writeProblemResponse(
            ServerWebExchange exchange,
            HttpStatus status,
            String title,
            String detail,
            String errorTypeUri,
            Map<String, String> additionalHeaders
    ) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        if (additionalHeaders != null) {
            additionalHeaders.forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(errorTypeUri));
        String instancePath = (exchange != null && exchange.getRequest() != null && exchange.getRequest().getPath() != null)
                ? exchange.getRequest().getPath().value()
                : "/";
        try {
            problem.setInstance(URI.create(instancePath));
        } catch (IllegalArgumentException e) {
            problem.setInstance(URI.create("/error"));
        }
        problem.setProperty("timestamp", Instant.now());

        byte[] bytes;
        try {
            bytes = OBJECT_MAPPER.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            bytes = ("{\"status\":" + status.value() + ",\"title\":\"" + title + "\",\"detail\":\"" + detail + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
