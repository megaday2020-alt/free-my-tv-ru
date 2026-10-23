package io.freemytv;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The TV's time zone ("Europe/Moscow") as a place: its main city, position and country, from the tz database (assets/zones.json,
 * made from web/zones.js). It gives a generic build (no city in config.properties) a sensible weather location and region
 * on any TV, until the person picks a city in the settings.
 */
final class Zones {
    private Zones() {}

    private static String[] guess;
    private static boolean done;

    /** {city, latitude, longitude, country} for this TV's time zone, or null when it is not in the table. */
    static synchronized String[] guess(Context c) {
        if (done) return guess;
        done = true;
        try (InputStream in = c.getAssets().open("zones.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            JSONObject zones = new JSONObject(out.toString("UTF-8"));
            String id = TimeZone.getDefault().getID();
            String v = zones.optString(id, "");
            for (int i = 0; i < 5 && v.startsWith("="); i++) v = zones.optString(v.substring(1), "");
            String[] p = v.split(",");
            if (p.length < 3) return null;
            String city = p.length > 3 ? p[3] : id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
            guess = new String[] {city, p[1], p[2], p[0].toUpperCase(Locale.ROOT)};
        } catch (Exception e) {
            AppLog.w("zones: " + e.getMessage());
        }
        return guess;
    }
}
