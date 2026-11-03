package com.rideshare.gateway.filter;

import com.rideshare.gateway.util.ProblemDetailWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

@Component
@Slf4j
public class PathSanitizationGlobalFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = -100;

    private static final Pattern MALFORMED_PERCENT_PATTERN = Pattern.compile("%(?![0-9a-fA-F]{2})");
    private static final Pattern ENCODED_SEPARATOR_PATTERN = Pattern.compile("%(2[fF]|5[cC])");
    private static final Pattern DOUBLE_ENCODING_PATTERN = Pattern.compile("%25", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOT_SEGMENT_PATTERN = Pattern.compile("(^|/)\\.\\.(/|$)|(^|/)\\.(/|$)");
    private static final Pattern DUPLICATE_SLASHES_PATTERN = Pattern.compile("/{2,}");
    private static final Pattern CONTROL_CHARS_PATTERN = Pattern.compile("[\u0000-\u001F\u007F]|%00", Pattern.CASE_INSENSITIVE);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();

        if (rawPath == null || rawPath.isBlank()) {
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "Invalid empty URI path",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        // 1. Control characters or null bytes
        if (CONTROL_CHARS_PATTERN.matcher(rawPath).find()) {
            log.warn("Path sanitization rejected control character or null byte in path: {}", rawPath);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "URI path contains illegal control characters or null bytes",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        // 2. Malformed percent encoding (e.g., %g1, dangling %)
        if (MALFORMED_PERCENT_PATTERN.matcher(rawPath).find()) {
            log.warn("Path sanitization rejected malformed percent-encoding in path: {}", rawPath);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "Malformed percent-encoding in URI path",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        // 3. Encoded slashes or backslashes (%2f, %5c)
        if (ENCODED_SEPARATOR_PATTERN.matcher(rawPath).find()) {
            log.warn("Path sanitization rejected encoded path separator in path: {}", rawPath);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "Encoded path separators (%2F or %5C) are strictly forbidden",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        // 4. Double encoding (%25)
        if (DOUBLE_ENCODING_PATTERN.matcher(rawPath).find()) {
            log.warn("Path sanitization rejected double percent-encoding in path: {}", rawPath);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "Double percent-encoding is strictly forbidden",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        // 5. Dot segments / directory traversal (.. or .)
        if (DOT_SEGMENT_PATTERN.matcher(rawPath).find()) {
            log.warn("Path sanitization rejected directory traversal segment in path: {}", rawPath);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "Path traversal sequences are strictly forbidden",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        // 6. Duplicate path separators (//)
        if (DUPLICATE_SLASHES_PATTERN.matcher(rawPath).find()) {
            log.warn("Path sanitization rejected duplicate path separators in path: {}", rawPath);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "Duplicate path separators are forbidden",
                    "https://api.rideshare.com/errors/bad-request"
            );
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
