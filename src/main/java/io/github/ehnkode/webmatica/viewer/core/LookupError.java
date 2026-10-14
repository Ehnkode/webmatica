package io.github.ehnkode.webmatica.viewer.core;

/** Something asked for does not exist (Python's LookupError): answered with 404. */
public class LookupError extends RuntimeException {
    public LookupError(String msg) {
        super(msg);
    }
}
