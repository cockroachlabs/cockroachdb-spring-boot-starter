package com.cockroachlabs.data.continuum.mcp;

import com.cockroachlabs.data.continuum.mcp.McpClient.CallResult;
import com.cockroachlabs.data.continuum.mcp.McpClient.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class McpClientTest {

    private static final String ENDPOINT = "http://mcp";

    @Test
    void factorySelectsSimulatedWhenNoEndpoint() {
        assertThat(ContinuumMcpFactory.create(new McpClient.Config(false, "", "")))
                .isInstanceOf(SimulatedMcpClient.class);
        assertThat(ContinuumMcpFactory.create(new McpClient.Config(true, "http://mcp", "tok")))
                .isInstanceOf(HttpMcpClient.class);
    }

    @Test
    void simulatedListsToolsAndEchoes() {
        SimulatedMcpClient c = new SimulatedMcpClient();
        assertThat(c.listTools()).isNotEmpty();
        assertThat(c.callTool("ping", Map.of()).text()).isEqualTo("pong");
        assertThat(c.callTool("echo", Map.of("k", "v")).text()).contains("\"k\":\"v\"");
        assertThat(c.calls()).hasSize(2);
    }

    @Test
    void httpInitializeListEchoesSessionAndParses() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT)
                .defaultHeader("Authorization", "Bearer tok");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}",
                MediaType.APPLICATION_JSON).header("Mcp-Session-Id", "sess-123"));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(ENDPOINT))
                .andExpect(header("Mcp-Session-Id", "sess-123"))
                .andExpect(header("MCP-Protocol-Version", "2025-06-18"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":"
                                + "[{\"name\":\"list_findings\",\"description\":\"d\"}]}}",
                        MediaType.APPLICATION_JSON));

        HttpMcpClient c = new HttpMcpClient(builder.build());
        List<Tool> tools = c.listTools();
        assertThat(tools).singleElement().extracting(Tool::name).isEqualTo("list_findings");
        server.verify();
    }

    @Test
    void httpSseResponseParsed() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,"
                        + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}\n\n",
                MediaType.TEXT_EVENT_STREAM));

        HttpMcpClient c = new HttpMcpClient(builder.build());
        CallResult res = c.callTool("list_findings", Map.of());
        assertThat(res.text()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void httpServerErrorSurfaced() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32601,\"message\":\"method not found\"}}",
                MediaType.APPLICATION_JSON));

        HttpMcpClient c = new HttpMcpClient(builder.build());
        assertThatThrownBy(c::listTools).hasMessageContaining("method not found");
    }
}
