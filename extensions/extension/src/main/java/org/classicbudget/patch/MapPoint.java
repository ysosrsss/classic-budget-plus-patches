package org.classicbudget.patch;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coordinates from a map pin or from the clipboard. */
final class MapPoint {
    private static final String NUMBER = "([+-]?\\d{1,3}(?:\\.\\d+)?)";
    private static final Pattern PAIR = Pattern.compile(NUMBER + "\\s*[,;]\\s*" + NUMBER);
    private static final Pattern PIN = Pattern.compile("!3d" + NUMBER + "!4d" + NUMBER + "(?=!|[/?&#]|$)");
    private static final Pattern LINK = Pattern.compile("(?:https://|geo:)[^\\s<>\"\\u200e\\u200f]+");
    private static final int MAX_SHARE = 4096;
    final double lat;
    final double lng;

    private MapPoint(double lat, double lng) {
        this.lat = lat == 0 ? 0 : lat;
        this.lng = lng == 0 ? 0 : lng;
    }

    boolean matches(double otherLat, double otherLng) {
        return lat == otherLat && lng == otherLng;
    }

    String coordinates() {
        return lat + ", " + lng;
    }

    static boolean acceptsLength(String text) {
        return text != null && !text.trim().isEmpty() && text.length() <= MAX_SHARE;
    }

    static MapPoint read(String text) {
        if (!acceptsLength(text)) return null;
        MapPoint direct = fromCoordinates(text);
        if (direct != null) return direct;
        Matcher links = LINK.matcher(text);
        if (!links.find()) return null;
        String link = links.group();
        if (links.find()) return null;
        try {
            URI uri = URI.create(link);
            if ("geo".equalsIgnoreCase(uri.getScheme())) {
                String[] parts = uri.getRawSchemeSpecificPart().split("\\?", 2);
                return parts.length == 2 ? query(parts[1]) : pair(parts[0]);
            }
            for (int redirects = 0; shortLink(uri) && redirects < 4; redirects++) {
                uri = redirect(uri);
                if (uri == null) return null;
            }
            if (!mapsLink(uri)) return null;
            MapPoint explicit = query(uri.getRawQuery());
            if (explicit != null) return explicit;
            // /@lat,lng encodes the camera position. The selected pin is in the 3d/4d pair.
            MapPoint pin = null;
            Matcher points = PIN.matcher(uri.toString());
            while (points.find()) {
                MapPoint next = from(points);
                if (next == null || (pin != null && !pin.matches(next.lat, next.lng))) return null;
                pin = next;
            }
            return pin;
        } catch (IOException | IllegalArgumentException unavailable) {
            return null;
        }
    }

    static MapPoint fromCoordinates(String text) {
        return acceptsLength(text) ? pair(text) : null;
    }

    private static MapPoint query(String raw) throws IOException {
        if (raw == null) return null;
        MapPoint point = null;
        for (String parameter : raw.split("&")) {
            String[] parts = parameter.split("=", 2);
            if (parts.length != 2 || !("q".equals(parts[0]) || "query".equals(parts[0]))) continue;
            MapPoint next = pair(URLDecoder.decode(parts[1], "UTF-8"));
            if (next == null || (point != null && !point.matches(next.lat, next.lng))) return null;
            point = next;
        }
        return point;
    }

    private static MapPoint pair(String text) {
        Matcher match = PAIR.matcher(text.trim());
        return match.matches() ? from(match) : null;
    }

    private static MapPoint from(Matcher match) {
        double lat = Double.parseDouble(match.group(1));
        double lng = Double.parseDouble(match.group(2));
        return Math.abs(lat) <= 90 && Math.abs(lng) <= 180 ? new MapPoint(lat, lng) : null;
    }

    private static boolean https(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static boolean shortLink(URI uri) {
        if (!https(uri) || uri.getRawPath() == null) return false;
        return ("maps.app.goo.gl".equalsIgnoreCase(uri.getHost()) && uri.getRawPath().matches("/[a-zA-Z0-9]+"))
            || ("goo.gl".equalsIgnoreCase(uri.getHost()) && uri.getRawPath().matches("/maps/[a-zA-Z0-9]+"));
    }

    private static boolean mapsLink(URI uri) {
        if (!https(uri) || uri.getRawPath() == null) return false;
        return ("www.google.com".equalsIgnoreCase(uri.getHost()) && ("/maps".equals(uri.getRawPath())
            || uri.getRawPath().startsWith("/maps/")))
            || "maps.google.com".equalsIgnoreCase(uri.getHost());
    }

    private static URI redirect(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        try {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            return status >= 300 && status < 400 && acceptsLength(location) ? uri.resolve(location) : null;
        } finally {
            connection.disconnect();
        }
    }
}
