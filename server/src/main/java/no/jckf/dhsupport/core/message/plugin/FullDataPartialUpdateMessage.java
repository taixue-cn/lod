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

package no.jckf.dhsupport.core.message.plugin;

import no.jckf.dhsupport.core.bytestream.Encoder;

public class FullDataPartialUpdateMessage extends PluginMessage
{
    protected String levelKey;

    protected int bufferId;

    protected byte[] beacons;

    public void setLevelKey(String levelKey)
    {
        this.levelKey = levelKey;
    }

    public String getLevelKey()
    {
        return levelKey;
    }

    public void setBufferId(int bufferId)
    {
        this.bufferId = bufferId;
    }

    public int getBufferId()
    {
        return bufferId;
    }

    public void setBeacons(byte[] beacons)
    {
        this.beacons = beacons;
    }

    public byte[] getBeacons()
    {
        return this.beacons;
    }

    @Override
    public int bufferSize()
    {
        return 32 + this.beacons.length;
    }

    @Override
    public void encode(Encoder encoder)
    {
        encoder.writeShortString(this.levelKey);
        encoder.writeInt(this.bufferId);
        encoder.write(this.beacons);
    }
}
