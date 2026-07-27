package io.vantage.loganalyzer;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds vantage.ollama.models — a list of named model options all served
 * from the same Ollama instance (one Windows PC, one GPU, multiple pulled
 * models). Each becomes a selectable entry in the chat UI's model picker.
 */
@Component
@ConfigurationProperties(prefix = "vantage.ollama")
public class VantageOllamaProperties {

    private List<ModelEntry> models = new ArrayList<>();

    public List<ModelEntry> getModels() {
        return models;
    }

    public void setModels(List<ModelEntry> models) {
        this.models = models;
    }

    public static class ModelEntry {
        private String key;
        private String label;
        private String modelName;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }
}
