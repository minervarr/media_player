package com.example.media_player.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

public class UriUtils {

    public static String getPathFromTreeUri(Uri treeUri, Context context) {
        if (treeUri == null) return null;
        
        String path = treeUri.getPath();
        if (path != null && path.startsWith("/tree/primary:")) {
            String relativePath = path.substring("/tree/primary:".length());
            return Environment.getExternalStorageDirectory() + "/" + relativePath;
        } else if (path != null && path.startsWith("/tree/")) {
            // For SD cards or other storage it looks like /tree/XXXX-XXXX:relativePath
            String segment = path.substring("/tree/".length());
            String[] parts = segment.split(":", 2);
            if (parts.length == 2) {
                // Return a /storage/XXXX-XXXX/relativePath path
                return "/storage/" + parts[0] + "/" + parts[1];
            }
        }
        
        // If we can't figure it out, just return the uri path which probably won't work 
        // with java.io.File, but it's a fallback.
        return path;
    }
}
