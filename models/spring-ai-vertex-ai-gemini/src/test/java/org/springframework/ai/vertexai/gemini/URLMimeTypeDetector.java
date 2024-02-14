/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vertexai.gemini;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.ResourceUtils;

/**
 *
 * @author Christian Tzolov
 */

public class URLMimeTypeDetector {

    // image/png
    // image/jpeg
    // video/mov
    // video/mpeg
    // video/mp4
    // video/mpg
    // video/avi
    // video/wmv
    // video/mpegps
    // video/flv

    private static final Map<String, MimeType> SUPPORTED_MIME_TYPES_CACHE = new HashMap<>();

    static {
        // Add custom MIME type mappings here
        SUPPORTED_MIME_TYPES_CACHE.put("png", MimeTypeUtils.IMAGE_PNG);
        SUPPORTED_MIME_TYPES_CACHE.put("jpeg", MimeTypeUtils.IMAGE_JPEG);
        SUPPORTED_MIME_TYPES_CACHE.put("jpg", MimeTypeUtils.IMAGE_JPEG);
        SUPPORTED_MIME_TYPES_CACHE.put("gif", MimeTypeUtils.IMAGE_GIF);
        SUPPORTED_MIME_TYPES_CACHE.put("mov", new MimeType("video", "mov"));
        SUPPORTED_MIME_TYPES_CACHE.put("mp4", new MimeType("video", "mp4"));
        SUPPORTED_MIME_TYPES_CACHE.put("mpg", new MimeType("video", "mpg"));
        SUPPORTED_MIME_TYPES_CACHE.put("avi", new MimeType("video", "avi"));
        SUPPORTED_MIME_TYPES_CACHE.put("wmv", new MimeType("video", "wmv"));
        SUPPORTED_MIME_TYPES_CACHE.put("mpegps", new MimeType("mpegps", "mp4"));
        SUPPORTED_MIME_TYPES_CACHE.put("flv", new MimeType("video", "flv"));
        // Add more mappings as needed
    }

    public static void main(String[] args) throws URISyntaxException, MalformedURLException {

        String urlString = "https://example.com/path/to/file.jpeg";

        System.out.println("MIME Type (URI): " + getMimeType(new URI(urlString)));
        System.out.println("MIME Type (URL): " + getMimeType(new URL(urlString)));
        System.out.println("MIME Type (Resource): " + getMimeType(new DefaultResourceLoader().getResource(urlString)));
    }

    public static MimeType getMimeType(URL url) {
        return getMimeType(url.getFile());
    }

    public static MimeType getMimeType(URI uri) {
        return getMimeType(uri.toString());
    }

    public static MimeType getMimeType(File file) {
        return getMimeType(file.getAbsolutePath());
    }

    public static MimeType getMimeType(Path path) {
        return getMimeType(path.getFileName());
    }

    public static MimeType getMimeType(Resource resource) {
        try {
            return getMimeType(resource.getURI());
        }
        catch (IOException e) {
            throw new IllegalArgumentException(
                    String.format("Unable to detect the MIME type of '%s'. Please provide it explicitly.",
                            resource.getFilename()),
                    e);
        }
    }

    public static MimeType getMimeType(String path) {

        int dotIndex = path.lastIndexOf('.');

        if (dotIndex != -1 && dotIndex < path.length() - 1) {
            String extension = path.substring(dotIndex + 1);
            MimeType customMimeType = SUPPORTED_MIME_TYPES_CACHE.get(extension);
            if (customMimeType != null) {
                return customMimeType;
            }
        }

        throw new IllegalArgumentException(
                String.format("Unable to detect the MIME type of '%s'. Please provide it explicitly.", path));
    }

}
