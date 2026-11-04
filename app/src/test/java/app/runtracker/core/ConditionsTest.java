package app.runtracker.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import app.runtracker.core.Conditions.TempBand;
import app.runtracker.core.Conditions.TimeOfDay;

public class ConditionsTest {
    @Test
    public void timeOfDayBoundaries() {
        assertEquals(TimeOfDay.NIGHT, Conditions.timeOfDayForHour(0));
        assertEquals(TimeOfDay.NIGHT, Conditions.timeOfDayForHour(4));
        assertEquals(TimeOfDay.MORNING, Conditions.timeOfDayForHour(5));
        assertEquals(TimeOfDay.MORNING, Conditions.timeOfDayForHour(10));
        assertEquals(TimeOfDay.NOON, Conditions.timeOfDayForHour(11));
        assertEquals(TimeOfDay.NOON, Conditions.timeOfDayForHour(15));
        assertEquals(TimeOfDay.EVENING, Conditions.timeOfDayForHour(16));
        assertEquals(TimeOfDay.EVENING, Conditions.timeOfDayForHour(20));
        assertEquals(TimeOfDay.NIGHT, Conditions.timeOfDayForHour(21));
        assertEquals(TimeOfDay.NIGHT, Conditions.timeOfDayForHour(23));
    }

    @Test
    public void colourBandMatchesTheRoundedNumberShown() {
        assertEquals(TempBand.COLD, Conditions.tempBand(-5));
        assertEquals(TempBand.COLD, Conditions.tempBand(11.4));
        assertEquals("12°C", Conditions.temperature(11.5));
        assertEquals(TempBand.MILD, Conditions.tempBand(11.5));
        assertEquals(TempBand.MILD, Conditions.tempBand(24.4));
        assertEquals("25°C", Conditions.temperature(24.5));
        assertEquals(TempBand.HOT, Conditions.tempBand(24.5));
    }

    @Test
    public void temperatureFormatting() {
        assertEquals("-3°C", Conditions.temperature(-3.2));
        assertEquals("0°C", Conditions.temperature(-0.2));
    }
}
