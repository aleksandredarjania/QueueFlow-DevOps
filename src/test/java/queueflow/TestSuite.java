package queueflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight, zero-dependency unit/API test runner. Exit code is nonzero on failure. */
public final class TestSuite {
    private static int passed;
    private static int failed;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<String> failures = new ArrayList<>();

    private TestSuite() {}

    @FunctionalInterface
    interface TestCase { void run() throws Exception; }

    private static void test(String label, TestCase task) {
        try {
            task.run();
            passed++;
            System.out.println("  PASS  " + label);
        } catch (Throwable error) {
            failed++;
            failures.add(label + ": " + error.getMessage());
            System.out.println("  FAIL  " + label + " (" + error.getMessage() + ")");
        }
    }

    private static void same(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void contains(String actual, String snippet) {
        if (!actual.contains(snippet)) {
            throw new AssertionError("Expected response to contain " + snippet + " but got " + actual);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable task) {
        try { task.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + error);
        }
        throw new AssertionError("Expected exception: " + type.getSimpleName());
    }

    private static HttpResponse<String> request(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (method.equals("POST")) {
            builder.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(String name, String service) {
        return "customer=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&service=" + URLEncoder.encode(service, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("QueueFlow test suite (Java standard library)");
        System.out.println("--------------------------------------------");

        test("new queue starts empty", () -> same(new QueueManager().stats(),
                new QueueManager.Stats(0, 0, 0)));
        test("first ticket starts at #1001", () -> same(
                new QueueManager().issue("Anna", "Documents").number(), 1001));
        test("ticket numbers increase", () -> {
            QueueManager q = new QueueManager();
            q.issue("Anna", "Documents");
            same(q.issue("David", "Payments").number(), 1002);
        });
        test("customer whitespace is trimmed", () -> same(
                new QueueManager().issue("  Anna  ", "Documents").customer(), "Anna"));
        test("blank customer is rejected", () -> expect(IllegalArgumentException.class,
                () -> new QueueManager().issue(" ", "Documents")));
        test("one-character customer is rejected", () -> expect(IllegalArgumentException.class,
                () -> new QueueManager().issue("A", "Documents")));
        test("overlong customer is rejected", () -> expect(IllegalArgumentException.class,
                () -> new QueueManager().issue("A".repeat(41), "Documents")));
        test("unknown service is rejected", () -> expect(IllegalArgumentException.class,
                () -> new QueueManager().issue("Anna", "Unknown")));
        test("call next follows FIFO order", () -> {
            QueueManager q = new QueueManager();
            q.issue("Anna", "Documents");
            q.issue("David", "Payments");
            same(q.callNext().customer(), "Anna");
            q.finishCurrent();
            same(q.callNext().customer(), "David");
        });
        test("cannot call next during service", () -> {
            QueueManager q = new QueueManager();
            q.issue("Anna", "Documents");
            q.issue("David", "Technical");
            q.callNext();
            expect(IllegalStateException.class, q::callNext);
        });
        test("cannot call next when queue is empty", () -> expect(
                IllegalStateException.class, () -> new QueueManager().callNext()));
        test("cannot finish when no ticket is active", () -> expect(
                IllegalStateException.class, () -> new QueueManager().finishCurrent()));
        test("finish marks ticket completed", () -> {
            QueueManager q = new QueueManager();
            q.issue("Anna", "Documents"); q.callNext();
            same(q.finishCurrent().status(), Ticket.Status.DONE);
        });
        test("statistics count each status", () -> {
            QueueManager q = new QueueManager();
            q.issue("Anna", "Documents");
            q.issue("David", "Payments");
            q.issue("Salome", "Technical");
            q.callNext(); q.finishCurrent(); q.callNext();
            same(q.stats(), new QueueManager.Stats(1, 1, 1));
        });
        test("ticket snapshots cannot be modified", () -> {
            QueueManager q = new QueueManager();
            q.issue("Anna", "Documents");
            expect(UnsupportedOperationException.class, () -> q.tickets().clear());
        });
        test("JSON strings escape unsafe characters", () -> same(
                Json.quote("A\"\\\nB"), "\"A\\\"\\\\\\nB\""));
        test("ticket JSON contains required fields", () -> contains(
                new QueueManager().issue("Anna", "Documents").toJson(), "\"status\":\"WAITING\""));
        test("concurrent issuance keeps unique ticket numbers", () -> {
            QueueManager q = new QueueManager();
            CountDownLatch latch = new CountDownLatch(12);
            AtomicInteger errors = new AtomicInteger();
            for (int i = 0; i < 12; i++) {
                new Thread(() -> {
                    try { q.issue("Client", "Documents"); }
                    catch (Exception e) { errors.incrementAndGet(); }
                    finally { latch.countDown(); }
                }).start();
            }
            latch.await();
            same(errors.get(), 0);
            same(q.tickets().stream().map(Ticket::number).distinct().count(), 12L);
        });

        System.out.println();
        System.out.println("HTTP smoke & API tests");
        try (QueueServer server = new QueueServer(new QueueManager(), 0, false)) {
            server.start();
            int port = server.port();
            test("health endpoint responds HTTP 200", () -> same(request(port, "GET", "/api/health", "").statusCode(), 200));
            test("health reports status ok", () -> contains(request(port, "GET", "/api/health", "").body(), "\"status\":\"ok\""));
            test("root page returns HTML", () -> contains(request(port, "GET", "/", "").body(), "QueueFlow"));
            test("stylesheet is served", () -> same(request(port, "GET", "/styles.css", "").statusCode(), 200));
            test("unknown route returns 404", () -> same(request(port, "GET", "/missing", "").statusCode(), 404));
            test("POST creates a ticket", () -> same(request(port, "POST", "/api/tickets", form("Nino", "Documents")).statusCode(), 201));
            test("GET returns issued ticket", () -> contains(request(port, "GET", "/api/tickets", "").body(), "\"customer\":\"Nino\""));
            test("bad customer returns 400", () -> same(request(port, "POST", "/api/tickets", form("A", "Documents")).statusCode(), 400));
            test("next endpoint calls first visitor", () -> contains(request(port, "POST", "/api/next", "").body(), "\"status\":\"SERVING\""));
            test("next endpoint returns 409 while active", () -> same(request(port, "POST", "/api/next", "").statusCode(), 409));
            test("finish endpoint completes visitor", () -> contains(request(port, "POST", "/api/finish", "").body(), "\"status\":\"DONE\""));
            test("statistics reflect completed ticket", () -> contains(request(port, "GET", "/api/stats", "").body(), "\"done\":1"));
        }
        try (QueueServer broken = new QueueServer(new QueueManager(), 0, true)) {
            broken.start();
            test("unhealthy deployment returns HTTP 503", () -> same(request(broken.port(), "GET", "/api/health", "").statusCode(), 503));
        }
        System.out.printf("%nRESULT: %d passed, %d failed, %d total%n", passed, failed, passed + failed);
        if (failed != 0) {
            failures.forEach(System.err::println);
            System.exit(1);
        }
    }
}
