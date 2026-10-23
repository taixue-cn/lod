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

public class FullDataChunkMessage extends PluginMessage
{
    protected int bufferId;

    protected byte[] data;

    protected int offset;

    protected int length;

    protected boolean isFirst = true;

    public void setBufferId(int bufferId)
    {
        this.bufferId = bufferId;
    }

    public int getBufferId()
    {
        return this.bufferId;
    }

    public void setData(byte[] data, int offset, int length)
    {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    public byte[] getData()
    {
        return this.data;
    }

    public void setIsFirst(boolean isFirst)
    {
        this.isFirst = isFirst;
    }

    public boolean getIsFirst()
    {
        return this.isFirst;
    }

    @Override
    public int bufferSize()
    {
        return 32 + this.length;
    }

    @Override
    public void encode(Encoder encoder)
    {
        encoder.writeInt(this.bufferId);
        encoder.writeInt(this.length);
        encoder.write(this.data, this.offset, this.length);
        encoder.writeBoolean(this.isFirst);
    }
}
