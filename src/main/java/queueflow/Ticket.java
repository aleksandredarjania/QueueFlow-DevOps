package queueflow;

public record Ticket(int number, String customer, String service, Status status) {
    public enum Status { WAITING, SERVING, DONE }

    public Ticket withStatus(Status nextStatus) {
        return new Ticket(number, customer, service, nextStatus);
    }

    public String toJson() {
        return "{\"number\":" + number
                + ",\"customer\":" + Json.quote(customer)
                + ",\"service\":" + Json.quote(service)
                + ",\"status\":" + Json.quote(status.name()) + "}";
    }
}
