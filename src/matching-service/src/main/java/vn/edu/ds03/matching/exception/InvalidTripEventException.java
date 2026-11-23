package vn.edu.ds03.matching.exception;

public class InvalidTripEventException extends RuntimeException {
    public InvalidTripEventException(String message) {
        super(message);
    }

    public InvalidTripEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
