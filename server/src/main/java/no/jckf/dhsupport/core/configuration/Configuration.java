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

package no.jckf.dhsupport.core.configuration;

import org.checkerframework.checker.nullness.qual.PolyNull;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Configuration
{
    protected Map<String, Object> variables = new ConcurrentHashMap<>();

    public void clear()
    {
        this.variables.clear();
    }

    public void clear(String prefix)
    {
        this.variables.keySet().removeIf((key) -> key.startsWith(prefix));
    }

    public boolean has(String key)
    {
        return this.variables.containsKey(key);
    }

    public void set(String key, @Nullable Object value)
    {
        if (value instanceof String stringValue) {
            if ("false".equalsIgnoreCase(stringValue)) {
                value = false;
            } else if ("true".equalsIgnoreCase(stringValue)) {
                value = true;
            } else {
                try {
                    value = Integer.parseInt(stringValue);
                } catch (NumberFormatException exception) {}
            }
        }

        this.variables.put(key, value);
    }

    public void unset(String key)
    {
        this.variables.remove(key);
    }

    public @PolyNull Object get(String key, @PolyNull Object defaultValue)
    {
        return this.variables.getOrDefault(key, defaultValue);
    }

    public @Nullable Object get(String key)
    {
        return this.get(key, null);
    }

    public @PolyNull Boolean getBool(String key, @PolyNull Boolean defaultValue)
    {
        return (Boolean) this.get(key, defaultValue);
    }

    public @Nullable Boolean getBool(String key)
    {
        return this.getBool(key, null);
    }

    public @PolyNull Integer getInt(String key, @PolyNull Integer defaultValue)
    {
        return (Integer) this.get(key, defaultValue);
    }

    public @Nullable Integer getInt(String key)
    {
        return this.getInt(key, null);
    }

    public @PolyNull String getString(String key, @PolyNull String defaultValue)
    {
        Object raw = this.get(key, defaultValue);

        return raw == null ? null : String.valueOf(raw);
    }

    public @Nullable String getString(String key)
    {
        return this.getString(key, null);
    }

    public @PolyNull List<String> getStringList(String key, @PolyNull List<String> defaultValue)
    {
        return (List<String>) this.get(key, defaultValue);
    }

    public List<String> getStringList(String key)
    {
        return this.getStringList(key, Collections.emptyList());
    }

    public int increment(String key)
    {
        return (int) this.variables.compute(key, (k, v) -> v == null ? 1 : (int) v + 1);
    }

    public int decrement(String key)
    {
        return (int) this.variables.compute(key, (k, v) -> v == null ? -1 : (int) v - 1);
    }
}
