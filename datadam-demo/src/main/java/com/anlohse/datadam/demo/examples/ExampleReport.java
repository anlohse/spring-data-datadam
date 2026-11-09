package com.anlohse.datadam.demo.examples;

import java.util.List;

/** Machine-readable outcome; business payloads and credentials are intentionally absent. */
public record ExampleReport(String scenario, String status, List<String> checks) {}
