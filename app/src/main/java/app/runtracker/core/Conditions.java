package app.runtracker.core;

import java.util.Calendar;
import java.util.Locale;

/** How a run's start time and air temperature are grouped for display. */
public final class Conditions {
    private Conditions() {
    }

    public enum TimeOfDay { MORNING, NOON, EVENING, NIGHT }

    public enum TempBand { COLD, MILD, HOT }

    /** Morning 05:00-10:59, noon 11:00-15:59, evening 16:00-20:59, night 21:00-04:59. */
    public static TimeOfDay timeOfDayForHour(int hour) {
        if (hour >= 5 && hour < 11) {
            return TimeOfDay.MORNING;
        }
        if (hour >= 11 && hour < 16) {
            return TimeOfDay.NOON;
        }
        if (hour >= 16 && hour < 21) {
            return TimeOfDay.EVENING;
        }
        return TimeOfDay.NIGHT;
    }

    public static TimeOfDay timeOfDay(long wallMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(wallMs);
        return timeOfDayForHour(calendar.get(Calendar.HOUR_OF_DAY));
    }

    /** Cold below 20 degrees, hot from 25. Uses the rounded value, so colour matches the number shown. */
    public static TempBand tempBand(double celsius) {
        long rounded = Math.round(celsius);
        if (rounded < 20) {
            return TempBand.COLD;
        }
        return rounded < 25 ? TempBand.MILD : TempBand.HOT;
    }

    public static String temperature(double celsius) {
        return String.format(Locale.US, "%d°C", Math.round(celsius));
    }
}
