package io.vantage.agentcore.rag;

import java.util.List;

/**
 * Abstraction over whatever is actually producing embeddings — currently a
 * llamafile instance running in {@code --embedding} mode on Server1,
 * colocated with the other CPU-bound inference workloads per the hardware
 * plan. Kept as an interface so the RAG-consuming code (Jira similarity
 * search, docs/spec search) doesn't depend on llamafile specifically; the
 * implementation is what talks HTTP to Server1.
 */
public interface EmbeddingClient {

    /**
     * @param text the text to embed (already summarized/chunked by the
     *             caller — see the Jira-vs-specs summarization discussion;
     *             this method does no preprocessing of its own)
     * @return the embedding vector
     */
    List<Float> embed(String text);
}
