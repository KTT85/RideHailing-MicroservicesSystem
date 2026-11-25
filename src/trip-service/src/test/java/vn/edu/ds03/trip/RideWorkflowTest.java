package vn.edu.ds03.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RideWorkflowTest {
    @Test
    void chargesByDistanceAndVehicle() {
        var pickup = new Ride.Location(0, 0, null);
        var nearby = new Ride.Location(0, 0.01, null);
        var farther = new Ride.Location(0, 0.02, null);

        double bike = RideWorkflow.fare(new RideWorkflow.Input(pickup, nearby, "BIKE", "TRANSFER"));
        double car = RideWorkflow.fare(new RideWorkflow.Input(pickup, nearby, "CAR", "CASH"));
        double longerRide = RideWorkflow.fare(new RideWorkflow.Input(pickup, farther, "BIKE", "TRANSFER"));

        assertEquals(8896, bike);
        assertEquals(16679, car);
        assertEquals(17791, longerRide);
        assertTrue(longerRide > bike);
    }

    @Test
    void veryShortRidesStillHaveAPositiveAmount() {
        var pickup = new Ride.Location(21, 105, null);
        var dropoff = new Ride.Location(21, 105.000000001, null);
        assertEquals(1, RideWorkflow.fare(new RideWorkflow.Input(pickup, dropoff, "BIKE", "TRANSFER")));
    }
}
