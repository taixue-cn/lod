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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class UpdateChecker
{
    protected int projectId;

    public UpdateChecker(int projectId)
    {
        this.projectId = projectId;
    }

    protected JSONObject getLatestRelease() throws IOException
    {
        URL url = URI.create("https://gitlab.com/api/v4/projects/" + this.projectId + "/releases").toURL();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            return null;
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;

        while ((line = in.readLine()) != null) {
            response.append(line);
        }

        in.close();

        JSONArray releases = new JSONArray(response.toString());

        if (releases.isEmpty()) {
            return null;
        }

        return (JSONObject) releases.get(0);
    }

    protected String getLatestVersion()
    {
        try {
            return this.getLatestRelease().getString("tag_name");
        } catch (IOException exception) {

        }

        return null;
    }

    public boolean isLatestVersion(String version)
    {
        String latestVersionString = this.getLatestVersion();

        // If the version check fails, pretend like we're up to date.
        if (latestVersionString == null) {
            return true;
        }

        String[] myVersion = version.split("[.-]");
        String[] latestVersion = latestVersionString.split("\\.");

        for (int i = 0; i < 3; i++) {
            int mySegment = Integer.parseInt(myVersion[i]);
            int latestSegment = Integer.parseInt(latestVersion[i]);

            // Our version is newer than the latest release.
            if (mySegment > latestSegment) {
                return true;
            }

            // Our version is older than the latest release.
            if (mySegment < latestSegment) {
                return false;
            }
        }

        // Our version is equal to the latest release.
        return true;
    }
}
