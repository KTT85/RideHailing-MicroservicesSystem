package vn.edu.ds03.user.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RequestSizeLimitFilterTest {
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(65536);

    @Test
    void emptyAndExactlyLimitBodiesReachControllerUnchanged() throws Exception {
        for (int length : new int[]{0, 65536}) {
            byte[] body = "x".repeat(length).getBytes(StandardCharsets.UTF_8);
            var request = new MockHttpServletRequest("POST", "/auth/register");
            request.setContent(body);
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(request, response, (accepted, result) -> {
                called.set(true);
                assertArrayEquals(body, accepted.getInputStream().readAllBytes());
            });
            assertTrue(called.get());
        }
    }

    @Test
    void oversizedBodyReturns413WithOrWithoutContentLength() throws Exception {
        for (boolean unknownLength : new boolean[]{false, true}) {
            var request = new MockHttpServletRequest("POST", "/auth/login") {
                @Override public long getContentLengthLong() {
                    return unknownLength ? -1 : super.getContentLengthLong();
                }
            };
            request.setContent("x".repeat(65537).getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (accepted, result) -> fail("Oversized request must not reach controller"));
            assertEquals(413, response.getStatus());
            assertTrue(response.getContentType().startsWith("application/problem+json"));
        }
    }

    @Test
    void limitCountsUtf8BytesInsteadOfCharacters() throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/register");
        request.setContent("á".repeat(32769).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (accepted, result) -> fail("65538 UTF-8 bytes exceed limit"));
        assertEquals(413, response.getStatus());
    }
}
