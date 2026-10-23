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

import no.jckf.dhsupport.core.bytestream.Decoder;
import no.jckf.dhsupport.core.bytestream.Encoder;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.enums.GeneratorPlan;

public class RemotePlayerConfigMessage extends PluginMessage
{
    public static String[] KEYS = {
        DhsConfig.DISTANT_GENERATION_ENABLED,
        DhsConfig.RENDER_DISTANCE,
        DhsConfig.BORDER_CENTER_X,
        DhsConfig.BORDER_CENTER_Z,
        DhsConfig.BORDER_RADIUS,
        DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT,
        DhsConfig.REAL_TIME_UPDATES_ENABLED,
        DhsConfig.REAL_TIME_UPDATE_RADIUS,
        DhsConfig.LOGIN_DATA_SYNC_ENABLED,
        DhsConfig.LOGIN_DATA_SYNC_RADIUS,
        DhsConfig.LOGIN_DATA_SYNC_RC_LIMIT,
        DhsConfig.MAX_DATA_TRANSFER_SPEED,
    };

    protected int renderDistance;

    protected boolean distantGenerationEnabled;

    protected int clientLodRequestConcurrencyLimit;

    protected boolean realTimeUpdatesEnabled;

    protected int realTimeUpdateRadius;

    protected boolean loginDataSyncEnabled;

    protected int loginDataSyncRadius;

    protected int loginDataSyncRcLimit;

    protected int maxDataTransferSpeed;

    protected int borderCenterX;

    protected int borderCenterZ;

    protected int borderRadius;

    public void setRenderDistance(int distance)
    {
        this.renderDistance = distance;
    }

    public int getRenderDistance()
    {
        return this.renderDistance;
    }

    public void setDistantGenerationEnabled(boolean enabled)
    {
        this.distantGenerationEnabled = enabled;
    }

    public boolean getDistantGenerationEnabled()
    {
        return this.distantGenerationEnabled;
    }

    public void setClientLodRequestConcurrencyLimit(int limit)
    {
        this.clientLodRequestConcurrencyLimit = limit;
    }

    public int getClientLodRequestConcurrencyLimit()
    {
        return this.clientLodRequestConcurrencyLimit;
    }

    public void setRealTimeUpdatesEnabled(boolean enabled)
    {
        this.realTimeUpdatesEnabled = enabled;
    }

    public boolean isRealTimeUpdatesEnabled()
    {
        return this.realTimeUpdatesEnabled;
    }

    public void setRealTimeUpdateRadius(int radius)
    {
        this.realTimeUpdateRadius = radius;
    }

    public int getRealTimeUpdateRadius()
    {
        return this.realTimeUpdateRadius;
    }

    public void setLoginDataSyncEnabled(boolean enabled)
    {
        this.loginDataSyncEnabled = enabled;
    }

    public boolean getLoginDataSyncEnabled()
    {
        return this.loginDataSyncEnabled;
    }

    public void setLoginDataSyncRadius(int radius)
    {
        this.loginDataSyncRadius = radius;
    }

    public int getLoginDataSyncRadius()
    {
        return this.loginDataSyncRadius;
    }

    public void setLoginDataSyncRcLimit(int limit)
    {
        this.loginDataSyncRcLimit = limit;
    }

    public int getLoginDataSyncRcLimit()
    {
        return this.loginDataSyncRcLimit;
    }

    public void setMaxDataTransferSpeed(int speed)
    {
        this.maxDataTransferSpeed = speed;
    }

    public int getMaxDataTransferSpeed()
    {
        return this.maxDataTransferSpeed;
    }

    public void setBorderCenterX(int borderCenterX)
    {
        this.borderCenterX = borderCenterX;
    }

    public void setBorderCenterZ(int borderCenterZ)
    {
        this.borderCenterZ = borderCenterZ;
    }

    public void setBorderRadius(int borderRadius)
    {
        this.borderRadius = borderRadius;
    }

    public int getBorderCenterX()
    {
        return borderCenterX;
    }

    public int getBorderCenterZ()
    {
        return borderCenterZ;
    }

    public int getBorderRadius()
    {
        return borderRadius;
    }

    @Override
    public void encode(Encoder encoder)
    {
        // Sent as the most permissive plan, so the client's own plan decides the generation mode.
        encoder.writeByte((this.distantGenerationEnabled ? GeneratorPlan.SURFACE_THEN_CHUNKS : GeneratorPlan.DISABLED).ordinal());
        encoder.writeInt(this.renderDistance);

        encoder.writeInt(this.borderCenterX);
        encoder.writeInt(this.borderCenterZ);
        encoder.writeInt(this.borderRadius);

        encoder.writeInt(this.clientLodRequestConcurrencyLimit);

        encoder.writeBoolean(this.realTimeUpdatesEnabled);
        encoder.writeInt(this.realTimeUpdateRadius);

        encoder.writeBoolean(this.loginDataSyncEnabled);
        encoder.writeInt(this.loginDataSyncRadius);
        encoder.writeInt(this.loginDataSyncRcLimit);

        encoder.writeInt(this.maxDataTransferSpeed);
    }

    @Override
    public void decode(Decoder decoder)
    {
        this.distantGenerationEnabled = GeneratorPlan.byOrdinal(decoder.readByte()).generationEnabled;
        this.renderDistance = decoder.readInt();

        this.borderCenterX = decoder.readInt();
        this.borderCenterZ = decoder.readInt();
        this.borderRadius = decoder.readInt();

        this.clientLodRequestConcurrencyLimit = decoder.readInt();

        this.realTimeUpdatesEnabled = decoder.readBoolean();
        this.realTimeUpdateRadius = decoder.readInt();

        this.loginDataSyncEnabled = decoder.readBoolean();
        this.loginDataSyncRadius = decoder.readInt();
        this.loginDataSyncRcLimit = decoder.readInt();

        this.maxDataTransferSpeed = decoder.readInt();
    }

    public void fromConfiguration(Configuration config)
    {
        this.distantGenerationEnabled = config.getBool(DhsConfig.DISTANT_GENERATION_ENABLED);
        this.renderDistance = config.getInt(DhsConfig.RENDER_DISTANCE);

        Integer borderCenterX = config.getInt(DhsConfig.BORDER_CENTER_X);
        Integer borderCenterZ = config.getInt(DhsConfig.BORDER_CENTER_Z);
        Integer borderRadius = config.getInt(DhsConfig.BORDER_RADIUS);

        this.borderCenterX = borderCenterX == null ? 0 : borderCenterX;
        this.borderCenterZ = borderCenterZ == null ? 0 : borderCenterZ;
        this.borderRadius = borderRadius == null ? 0 : borderRadius;

        this.clientLodRequestConcurrencyLimit = config.getInt(DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT);

        this.realTimeUpdatesEnabled = config.getBool(DhsConfig.REAL_TIME_UPDATES_ENABLED);
        this.realTimeUpdateRadius = config.getInt(DhsConfig.REAL_TIME_UPDATE_RADIUS);

        this.loginDataSyncEnabled = config.getBool(DhsConfig.LOGIN_DATA_SYNC_ENABLED);
        this.loginDataSyncRadius = config.getInt(DhsConfig.LOGIN_DATA_SYNC_RADIUS);
        this.loginDataSyncRcLimit = config.getInt(DhsConfig.LOGIN_DATA_SYNC_RC_LIMIT);

        this.maxDataTransferSpeed = config.getInt(DhsConfig.MAX_DATA_TRANSFER_SPEED);
    }

    public Configuration toConfiguration()
    {
        Configuration config = new Configuration();

        config.set(DhsConfig.DISTANT_GENERATION_ENABLED, this.distantGenerationEnabled);
        config.set(DhsConfig.RENDER_DISTANCE, this.renderDistance);

        config.set(DhsConfig.BORDER_CENTER_X, this.borderCenterX);
        config.set(DhsConfig.BORDER_CENTER_Z, this.borderCenterZ);
        config.set(DhsConfig.BORDER_RADIUS, this.borderRadius);

        config.set(DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT, this.clientLodRequestConcurrencyLimit);

        config.set(DhsConfig.REAL_TIME_UPDATES_ENABLED, this.realTimeUpdatesEnabled);
        config.set(DhsConfig.REAL_TIME_UPDATE_RADIUS, this.realTimeUpdateRadius);

        config.set(DhsConfig.LOGIN_DATA_SYNC_ENABLED, this.loginDataSyncEnabled);
        config.set(DhsConfig.LOGIN_DATA_SYNC_RADIUS, this.loginDataSyncRadius);
        config.set(DhsConfig.LOGIN_DATA_SYNC_RC_LIMIT, this.loginDataSyncRcLimit);

        config.set(DhsConfig.MAX_DATA_TRANSFER_SPEED, this.maxDataTransferSpeed);

        return config;
    }
}
