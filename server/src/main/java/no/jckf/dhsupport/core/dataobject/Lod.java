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

package no.jckf.dhsupport.core.dataobject;

import no.jckf.dhsupport.core.bytestream.Encoder;
import no.jckf.dhsupport.core.enums.CompressionType;
import no.jckf.dhsupport.core.enums.GenerationStep;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * @see FullDataSourceV2DTO
 */
public class Lod extends DataObject
{
    public static int dataFormatVersion = 1;

    public static CompressionType compressionType = CompressionType.LZMA2;

    protected static final int MAX_DICT_SIZE = 1 << 20;

    public static int width = 64;

    protected static volatile byte[] compressedGenerationStep;

    protected static volatile byte[] compressedWorldCompressionType;

    protected WorldInterface worldInterface;

    protected SectionPosition position;

    protected List<IdMapping> idMappings;

    protected List<List<DataPoint>> columns;

    protected List<Beacon> beacons;

    public Lod(WorldInterface worldInterface, SectionPosition position, List<IdMapping> idMappings, List<List<DataPoint>> columns, List<Beacon> beacons)
    {
        this.worldInterface = worldInterface;
        this.position = position;
        this.idMappings = idMappings;
        this.columns = columns;
        this.beacons = beacons;
    }

    protected void encodeData(Encoder encoder)
    {
        Encoder toCompress = new Encoder(8192);

        for (List<DataPoint> column : this.columns) {
            toCompress.writeShort(column.size());

            for (DataPoint dataPoint : column) {
                dataPoint.encode(toCompress);
            }
        }

        byte[] compressed = this.compress(toCompress.toByteArray());

        encoder.writeInt(compressed.length);
        encoder.write(compressed);
    }

    protected void encodeAdjacentData(Encoder encoder)
    {
        encoder.writeInt(0);
        encoder.writeInt(0);
        encoder.writeInt(0);
        encoder.writeInt(0);
    }

    protected void encodeColumnGenerationStep(Encoder encoder)
    {
        byte[] compressed = Lod.compressedGenerationStep;

        if (compressed == null) {
            byte[] bytesToCompress = new byte[Lod.width * Lod.width];
            Arrays.fill(bytesToCompress, (byte) GenerationStep.LIGHT.value);

            compressed = this.compress(bytesToCompress);
            Lod.compressedGenerationStep = compressed;
        }

        encoder.writeInt(compressed.length);
        encoder.write(compressed);
    }

    protected void encodeWorldCompressionType(Encoder encoder)
    {
        byte[] compressed = Lod.compressedWorldCompressionType;

        if (compressed == null) {
            byte[] bytesToCompress = new byte[Lod.width * Lod.width]; // All zeroes means WorldCompressionType.STRICT

            compressed = this.compress(bytesToCompress);
            Lod.compressedWorldCompressionType = compressed;
        }

        encoder.writeInt(compressed.length);
        encoder.write(compressed);
    }

    protected void encodeMappings(Encoder encoder)
    {
        Encoder toCompress = new Encoder(1024);

        toCompress.writeInt(this.idMappings.size());

        for (IdMapping mapping : this.idMappings) {
            mapping.encode(toCompress);
        }

        byte[] compressed = this.compress(toCompress.toByteArray());

        encoder.writeInt(compressed.length);
        encoder.write(compressed);
    }

    @Override
    public void encode(Encoder encoder)
    {
        this.position.encode(encoder);

        long now = System.currentTimeMillis();

        encoder.writeInt(0); // Checksum

        this.encodeData(encoder);
        this.encodeAdjacentData(encoder);
        this.encodeColumnGenerationStep(encoder);
        this.encodeWorldCompressionType(encoder);
        this.encodeMappings(encoder);

        encoder.writeByte(Lod.dataFormatVersion);

        encoder.writeByte(Lod.compressionType.value);

        encoder.writeBoolean(true); // Apply to parent
        encoder.writeBoolean(false); // Apply to children
        encoder.writeBoolean(false); // Regenerate

        encoder.writeLong(now); // Created at
        encoder.writeLong(now); // Updated at
    }

    protected byte[] compress(byte[] uncompressedData)
    {
        ByteArrayOutputStream compressedStream = new ByteArrayOutputStream();

        try {
            LZMA2Options options = new LZMA2Options(3);

            int dictSize = LZMA2Options.DICT_SIZE_MIN;

            while (dictSize < uncompressedData.length && dictSize < MAX_DICT_SIZE) {
                dictSize <<= 1;
            }

            options.setDictSize(dictSize);

            try(OutputStream compressorStream = new XZOutputStream(compressedStream, options, XZ.CHECK_CRC64)) {
                compressorStream.write(uncompressedData);
            }
        } catch (Exception exception) {
            // Uhh...
            System.out.println(exception.getClass().getSimpleName() + " - " + exception.getMessage());
        }

        return compressedStream.toByteArray();
    }

    public List<Beacon> getBeacons()
    {
        return this.beacons;
    }
}
