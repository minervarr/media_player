package com.example.media_player;

/**
 * Helpers for canonicalizing artwork URLs and reconstructing per-tier
 * download URLs. The cache stores at most one THUMB and one FULL entry
 * per canonical key, so canonicalization must strip any size information
 * baked into the API URL.
 */
public final class ArtworkUrls {

    public enum Tier { THUMB, FULL }

    private ArtworkUrls() {}

    /**
     * Strip per-tier sizing from a raw Qobuz API URL → canonical cache key.
     * Qobuz CDN URLs look like ".../<hash>_<size>.jpg"; we keep everything
     * up to (but not including) the "_<size>.jpg" suffix.
     */
    public static String canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return null;
        int dot = rawUrl.lastIndexOf(".jpg");
        if (dot > 0) {
            int underscore = rawUrl.lastIndexOf('_', dot);
            int slash = rawUrl.lastIndexOf('/', dot);
            if (underscore > slash) {
                return "qobuz:" + rawUrl.substring(0, underscore);
            }
        }
        return "qobuz:" + rawUrl;
    }

    /**
     * Reconstruct the per-tier download URL from a canonical key. Returns
     * null for keys that don't have an HTTP source (e.g. local "album:" keys).
     */
    public static String downloadUrl(String canonicalKey, Tier tier) {
        if (canonicalKey == null) return null;
        if (canonicalKey.startsWith("qobuz:")) {
            String base = canonicalKey.substring(6);
            return base + (tier == Tier.FULL ? "_org.jpg" : "_600.jpg");
        }
        if (canonicalKey.startsWith("tidal:")) {
            String path = canonicalKey.substring(6);
            return "https://resources.tidal.com/images/" + path
                    + (tier == Tier.FULL ? "/1280x1280.jpg" : "/640x640.jpg");
        }
        return null;
    }

    /** Pick the disk-cache tier appropriate for a target display size. */
    public static Tier tierForSize(int sizePx) {
        return sizePx > 600 ? Tier.FULL : Tier.THUMB;
    }

    /** True if this key represents an HTTP-fetchable artwork (qobuz: / tidal:). */
    public static boolean isRemote(String canonicalKey) {
        return canonicalKey != null
                && (canonicalKey.startsWith("qobuz:") || canonicalKey.startsWith("tidal:"));
    }
}
