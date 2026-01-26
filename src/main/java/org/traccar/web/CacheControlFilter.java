/*
 * Copyright 2012 - 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CacheControlFilter implements Filter {

    private static final String NO_CACHE = "no-cache, no-store, must-revalidate";
    private static final String ASSET_CACHE = "public, max-age=31536000, immutable";

    private static boolean isNoCachePath(String path) {
        return path.equals("/")
                || path.endsWith(".html")
                || path.endsWith("/sw.js")
                || path.endsWith("/service-worker.js")
                || path.endsWith("manifest.webmanifest");
    }

    private static boolean isAssetPath(String path) {
        return path.startsWith("/assets/")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".woff2")
                || path.endsWith(".woff")
                || path.endsWith(".mp3")
                || path.endsWith(".png")
                || path.endsWith(".jpg")
                || path.endsWith(".jpeg")
                || path.endsWith(".svg");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        if (path.startsWith("/api/") || path.startsWith("/api")) {
            chain.doFilter(request, response);
            return;
        }

        String cacheControl = null;
        if (isNoCachePath(path)) {
            cacheControl = NO_CACHE;
            httpResponse.setHeader("Pragma", "no-cache");
            httpResponse.setDateHeader("Expires", 0);
        } else if (isAssetPath(path)) {
            cacheControl = ASSET_CACHE;
        }

        if (cacheControl != null) {
            httpResponse.setHeader("Cache-Control", cacheControl);
        }

        chain.doFilter(request, response);
    }

}
