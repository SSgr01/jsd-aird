package com.jsd.aird.kb.domain;

import java.io.InputStream;

public interface FileSafetyScanner {

    ScanResult scan(InputStream source, String fileName, String contentType, long size);

    record ScanResult(Status status, String reason) {
        public enum Status { SAFE, REJECTED, UNAVAILABLE }
    }
}
