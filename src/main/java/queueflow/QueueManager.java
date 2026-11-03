package queueflow;

import java.util.ArrayList;
import java.util.List;

/** In-memory queue with exactly one active service desk. */
public final class QueueManager {
    private final List<Ticket> tickets = new ArrayList<>();
    private int nextNumber = 1001;

    public synchronized Ticket issue(String customer, String service) {
        String name = customer == null ? "" : customer.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 2 || length > 40) {
            throw new IllegalArgumentException("Name must contain 2–40 characters.");
        }
        if (!List.of("Documents", "Payments", "Technical").contains(service)) {
            throw new IllegalArgumentException("Choose a valid service.");
        }
        Ticket ticket = new Ticket(nextNumber++, name, service, Ticket.Status.WAITING);
        tickets.add(ticket);
        return ticket;
    }

    public synchronized Ticket callNext() {
        if (tickets.stream().anyMatch(t -> t.status() == Ticket.Status.SERVING)) {
            throw new IllegalStateException("Finish the current ticket first.");
        }
        for (int i = 0; i < tickets.size(); i++) {
            Ticket ticket = tickets.get(i);
            if (ticket.status() == Ticket.Status.WAITING) {
                Ticket next = ticket.withStatus(Ticket.Status.SERVING);
                tickets.set(i, next);
                return next;
            }
        }
        throw new IllegalStateException("There are no waiting tickets.");
    }

    public synchronized Ticket finishCurrent() {
        for (int i = 0; i < tickets.size(); i++) {
            Ticket ticket = tickets.get(i);
            if (ticket.status() == Ticket.Status.SERVING) {
                Ticket done = ticket.withStatus(Ticket.Status.DONE);
                tickets.set(i, done);
                return done;
            }
        }
        throw new IllegalStateException("There is no active ticket.");
    }

    public synchronized List<Ticket> tickets() {
        return List.copyOf(tickets);
    }

    public synchronized Stats stats() {
        int waiting = 0, serving = 0, done = 0;
        for (Ticket ticket : tickets) {
            switch (ticket.status()) {
                case WAITING -> waiting++;
                case SERVING -> serving++;
                case DONE -> done++;
            }
        }
        return new Stats(waiting, serving, done);
    }

    public record Stats(int waiting, int serving, int done) {
        public String toJson() {
            return "{\"waiting\":" + waiting + ",\"serving\":" + serving
                    + ",\"done\":" + done + "}";
        }
    }
}
