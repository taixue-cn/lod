/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package no.jckf.dhsupport.core;

import java.util.ArrayList;
import java.util.List;

public class PerformanceTracker
{
    private static final int WINDOW_SIZE_MILLIS = 60 * 3 * 1000;

    private final List<Long> pings = new ArrayList<>();

    public synchronized void ping()
    {
        this.pings.add(System.currentTimeMillis());
    }

    public synchronized void prune()
    {
        long currentTimeMillis = System.currentTimeMillis();

        this.pings.removeIf((pingTimeMillis) -> currentTimeMillis - pingTimeMillis > WINDOW_SIZE_MILLIS);
    }

    public double getPingsPerSecond()
    {
        if (this.pings.isEmpty()) {
            return 0;
        }

        // Use the earliest ping to calculate window size if we don't have data for the full period.
        long windowSize = Math.min(
            WINDOW_SIZE_MILLIS / 1000,
            System.currentTimeMillis() - this.pings.get(0)
        );

        this.prune();

        return (double) this.pings.size() / windowSize;
    }
}
