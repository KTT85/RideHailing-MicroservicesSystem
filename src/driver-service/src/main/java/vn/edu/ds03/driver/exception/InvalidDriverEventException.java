package vn.edu.ds03.driver.exception;

public class InvalidDriverEventException extends RuntimeException {
    public InvalidDriverEventException(String message) {
        super(message);
    }

    public InvalidDriverEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
