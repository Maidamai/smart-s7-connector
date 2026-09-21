/*
Copyright 2016 S7connector members (github.com/s7connector)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.github.maidamai.s7connector.impl.serializer.converter;

import io.github.maidamai.s7connector.exception.S7Exception;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateAndTimeConverterUpstreamRegressionTest {

    @Test
    void bcdRoundTripsEveryTwoDigitValue() {
        final DateAndTimeConverter converter = new DateAndTimeConverter();
        final byte[] buffer = new byte[1];

        for (int value = 0; value < 100; value++) {
            converter.putAsBCD(buffer, 0, value);
            assertEquals(value, converter.getValueFromBCD(buffer, 0) & 0xFF,
                    "BCD round trip for " + value);
        }
    }

    @Test
    void extractsMillisecondsFromS7DateAndTime() {
        final DateAndTimeConverter converter = new DateAndTimeConverter();
        final byte[] buffer = new byte[]{
                0x25, 0x08, 0x13,
                0x11, 0x24, 0x01,
                0x68, 0x64
        };

        final Date result = converter.extract(Date.class, buffer, 0, 0);

        assertEquals(dateAt(2025, Calendar.AUGUST, 13, 11, 24, 1, 686), result);
    }

    @Test
    void insertAndExtractPreserveMillisecondsAndWeekdayNibble() {
        final DateAndTimeConverter converter = new DateAndTimeConverter();
        final byte[] buffer = new byte[8];
        final Date input = dateAt(2025, Calendar.JANUARY, 14, 1, 12, 9, 197);

        converter.insert(input, buffer, 0, 0, buffer.length);

        assertEquals(0x19, buffer[6] & 0xFF, "hundreds/tens of milliseconds");
        assertEquals(0x73, buffer[7] & 0xFF,
                "ones of milliseconds in high nibble and Tuesday=3 in low nibble");
        assertEquals(input, converter.extract(Date.class, buffer, 0, 0));
    }

    @Test
    void rejectsYearsOutsideS7DateAndTimeRange() {
        final DateAndTimeConverter converter = new DateAndTimeConverter();
        final byte[] buffer = new byte[8];

        assertThrows(S7Exception.class,
                () -> converter.insert(dateAt(1989, Calendar.DECEMBER, 31, 23, 59, 59, 999),
                        buffer, 0, 0, buffer.length));
        assertThrows(S7Exception.class,
                () -> converter.insert(dateAt(2090, Calendar.JANUARY, 1, 0, 0, 0, 0),
                        buffer, 0, 0, buffer.length));
    }

    @Test
    void insertFailsWhenDateAndTimeBufferIsTooShort() {
        final DateAndTimeConverter converter = new DateAndTimeConverter();
        final byte[] buffer = new byte[7];

        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> converter.insert(dateAt(2025, Calendar.JANUARY, 14, 1, 12, 9, 197),
                        buffer, 0, 0, buffer.length));
    }

    private static Date dateAt(final int year, final int month, final int dayOfMonth,
                               final int hourOfDay, final int minute, final int second,
                               final int millis) {
        final Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month);
        calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, millis);
        return calendar.getTime();
    }
}
