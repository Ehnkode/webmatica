package io.github.ehnkode.webmatica.viewer.core;

/** A required key is missing (Python's KeyError): 400 in most routes, 404 where a lookup failed. */
public class KeyError extends IllegalArgumentException {
    public KeyError(String key) {
        super("'" + key + "'");
    }
}
