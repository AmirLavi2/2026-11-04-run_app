package app.runtracker.core;

/** How a trip is being travelled. Saved with each trip by its enum name. */
public enum TravelMode {
    /** 8 m/s is 2:05 per km. */
    RUN(8.0, 5.0, "run", "running"),
    /** 70 m/s is 252 km/h. */
    CAR(70.0, 20.0, "car", "driving"),
    MOTORCYCLE(70.0, 20.0, "motorcycle", "motorcycling");

    /** Readings implying a higher speed than this are treated as GNSS errors. */
    public final double maxSpeedMps;
    /** Smaller movements are treated as GNSS drift. */
    public final double minMoveM;
    /** Used in exported file names and the CSV. */
    public final String label;
    public final String gpxType;

    TravelMode(double maxSpeedMps, double minMoveM, String label, String gpxType) {
        this.maxSpeedMps = maxSpeedMps;
        this.minMoveM = minMoveM;
        this.label = label;
        this.gpxType = gpxType;
    }

    /** Vehicles show speed and stay silent; running shows pace and plays tones. */
    public boolean isVehicle() {
        return this != RUN;
    }

    /** Falls back to RUN for a missing or unknown name. */
    public static TravelMode fromName(String name) {
        for (TravelMode mode : values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return RUN;
    }
}
