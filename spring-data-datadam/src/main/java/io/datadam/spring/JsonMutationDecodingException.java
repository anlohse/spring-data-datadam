package io.datadam.spring;

import org.springframework.dao.DataRetrievalFailureException;

/** The server replied to a mutation, but its result could not be decoded. Do not blindly repeat the command. */
public final class JsonMutationDecodingException extends DataRetrievalFailureException {
    private final String key;
    private final String command;
    public JsonMutationDecodingException(String key, String command, Throwable cause) {
        super("Cannot decode " + command + " result after the server reply; the mutation may already have occurred", cause);
        this.key = key; this.command = command;
    }
    public String getKey() { return key; }
    public String getCommand() { return command; }
}
