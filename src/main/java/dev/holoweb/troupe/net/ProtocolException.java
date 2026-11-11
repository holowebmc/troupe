package dev.holoweb.troupe.net;

import java.io.IOException;

/** The other side broke the protocol; the connection should be closed with this message. */
public final class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}
