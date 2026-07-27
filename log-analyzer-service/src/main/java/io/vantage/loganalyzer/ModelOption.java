package io.vantage.loganalyzer;

/**
 * Matches VISTA's ChatService.ModelOption shape — key/label pair for the
 * frontend picker, provider distinguishes which ChatClient to route to,
 * modelName is the actual model identifier passed per-request (used for
 * Ollama, where one client can serve multiple pulled models).
 */
public record ModelOption(String key, String label, String provider, String modelName) {
}
