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

import no.jckf.dhsupport.core.world.WorldInterface;
import org.checkerframework.checker.nullness.qual.PolyNull;

import javax.annotation.Nullable;

/**
 * A view over a backing {@link Configuration}, scoped to a single world via a
 * {@code worlds.<name>.} key prefix. Holds no state of its own — every method reads or writes
 * through to the backing config, falling back to the unprefixed (global) key when a
 * world-specific value isn't set.
 */
public class WorldConfiguration extends Configuration
{
    protected static String WORLD_PREFIX = "worlds.%s.";

    protected WorldInterface world;

    protected Configuration config;

    public WorldConfiguration(WorldInterface world, Configuration config)
    {
        this.world = world;
        this.config = config;
    }

    protected String key(String key)
    {
        return WORLD_PREFIX.formatted(this.world.getName()) + key;
    }

    @Override
    public void set(String key, @Nullable Object value)
    {
        this.config.set(this.key(key), value);
    }

    @Override
    public void unset(String key)
    {
        this.config.unset(this.key(key));
    }

    @Override
    public @PolyNull Object get(String key, @PolyNull Object defaultValue)
    {
        Object specific = this.config.get(this.key(key));

        return specific == null ? this.config.get(key, defaultValue) : specific;
    }

    @Override
    public boolean has(String key)
    {
        // Mirror get()'s resolution order: world-specific first, then the global key.
        return this.config.has(this.key(key)) || this.config.has(key);
    }

    @Override
    public void clear()
    {
        this.config.clear(WORLD_PREFIX.formatted(this.world.getName()));
    }

    @Override
    public int increment(String key)
    {
        return this.config.increment(this.key(key));
    }

    @Override
    public int decrement(String key)
    {
        return this.config.decrement(this.key(key));
    }
}
