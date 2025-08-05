/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.servlet;

import static io.jenkins.plugins.mcp.server.Endpoint.USER_ID;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_TOOLS_CALL;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.User;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class UserContextHttpRequest extends HttpServletRequestWrapper {

    private final byte[] requestBody;

    public UserContextHttpRequest(ObjectMapper objectMapper, HttpServletRequest request) throws IOException {
        super(request);
        // 读取原始 request body
        BufferedReader reader = request.getReader();
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }

        var bodyString = body.toString().trim();

        if (!bodyString.isEmpty()) {

            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, bodyString);
            if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
                    && jsonrpcRequest.method().equals(METHOD_TOOLS_CALL)) {

                var currentUser = User.current();
                String userId = null;
                if (currentUser != null) {
                    userId = currentUser.getId();
                }
                if (jsonrpcRequest.params() instanceof Map params && userId != null) {
                    Map meta = (Map) params.getOrDefault("_meta", new HashMap<>());
                    meta.put(USER_ID, userId);
                    params.put("_meta", meta);
                    bodyString = objectMapper.writeValueAsString(jsonrpcRequest);
                }
            }
        }
        if (bodyString.isEmpty()) {
            requestBody = new byte[0];
        } else if (request.getCharacterEncoding() != null) {
            requestBody = bodyString.getBytes(request.getCharacterEncoding());
        } else {
            requestBody = bodyString.getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(requestBody);

        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {}
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding()));
    }
}
