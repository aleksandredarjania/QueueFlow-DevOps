package queueflow;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = 8080;
        boolean failHealth = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--fail-health")) {
                failHealth = true; // Used only by the rollback verification script.
            } else {
                throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        QueueServer server = new QueueServer(new QueueManager(), port, failHealth);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.printf("QueueFlow running at http://localhost:%d%n", server.port());
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
