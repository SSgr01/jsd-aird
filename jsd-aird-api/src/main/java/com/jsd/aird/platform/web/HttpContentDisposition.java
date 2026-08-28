package com.jsd.aird.platform.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class HttpContentDisposition {

    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,10}$");

    private HttpContentDisposition() {
    }

    public static String inline(String fileName) {
        var safeName = fileName == null ? "" : fileName.replace("\r", "").replace("\n", "");
        var matcher = SAFE_EXTENSION.matcher(safeName);
        var fallback = "download" + (matcher.find() ? matcher.group() : "");
        var encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
