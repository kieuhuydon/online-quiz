package quiz.client.network;

/** Safe transport failure. Never carries server text, serialized payloads or credentials. */
public final class TransportException extends RuntimeException {
    public enum Kind { CONNECTION_LOST, TIMEOUT, PROTOCOL, OVERLOADED, CLOSED }
    private final Kind kind;

    public TransportException(Kind kind) {
        super(switch (kind) {
            case CONNECTION_LOST -> "Connection unavailable or lost";
            case TIMEOUT -> "Connection or request deadline exceeded; outcome may be unknown";
            case PROTOCOL -> "Invalid server response";
            case OVERLOADED -> "Client request capacity exceeded; request was not sent";
            case CLOSED -> "Client connection has been closed";
        });
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
