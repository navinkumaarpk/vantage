package io.vantage.mcp;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import javax.net.ssl.SSLContext;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <strong>Not verified against real dependencies</strong> — same caveat as
 * agent-core's original MCP client wiring: written to the well-established
 * Elastic Java API client + Apache HttpClient 4.x pattern, but not compiled
 * against the actual jars in this sandbox. Higher confidence than the
 * MCP-server-specific wiring in {@link LogSearchTools} though, since this
 * API shape has been stable for years.
 *
 * <p>TLS verification is disabled (trust-all) to match the self-signed certs
 * from Elasticsearch's default single-node security auto-configuration.
 * Fine for this internal, colocated setup — swap for real CA verification
 * before this is ever exposed beyond Server2 itself.
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${vantage.elasticsearch.host}")
    private String host;

    @Value("${vantage.elasticsearch.port}")
    private int port;

    @Value("${vantage.elasticsearch.username}")
    private String username;

    @Value("${vantage.elasticsearch.password}")
    private String password;

    @Bean
    public ElasticsearchClient elasticsearchClient() throws Exception {
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();

        RestClient restClient = RestClient.builder(new HttpHost(host, port, "https"))
                .setHttpClientConfigCallback(hc -> hc
                        .setSSLContext(sslContext)
                        .setDefaultCredentialsProvider(credentialsProvider))
                .build();

        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
