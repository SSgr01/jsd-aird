package com.jsd.aird.kb.api;

import java.io.InputStream;
import java.util.Set;

public interface FileSafetyScanner {

    Set<String> supportedExtensions();

    long maxFileBytes();

    ScanResult scan(InputStream source, String fileName, String contentType, long size);

    record ScanResult(Status status, String reason) {
        public enum Status { SAFE, REJECTED, UNAVAILABLE }
    }
}
