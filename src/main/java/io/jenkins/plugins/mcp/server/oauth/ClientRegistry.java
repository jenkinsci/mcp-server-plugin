/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.oauth;

import hudson.BulkChange;
import hudson.XmlFile;
import hudson.model.Saveable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ClientRegistry implements Saveable {

    private static final String FILE_NAME = "mcp-oauth-clients.xml";

    private static volatile ClientRegistry INSTANCE;

    private final ConcurrentMap<String, ClientRegistration> byId = new ConcurrentHashMap<>();

    public static synchronized ClientRegistry get() {
        if (INSTANCE == null) {
            INSTANCE = new ClientRegistry();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    public void register(ClientRegistration client) {
        byId.put(client.getClientId(), client);
        save();
    }

    public ClientRegistration find(String clientId) {
        return clientId == null ? null : byId.get(clientId);
    }

    public List<ClientRegistration> all() {
        return new ArrayList<>(byId.values());
    }

    private XmlFile file() {
        return new XmlFile(new File(Jenkins.get().getRootDir(), FILE_NAME));
    }

    @SuppressWarnings("unchecked")
    private void load() {
        XmlFile xml = file();
        if (!xml.exists()) return;
        try {
            List<ClientRegistration> loaded = (List<ClientRegistration>) xml.read();
            byId.clear();
            for (ClientRegistration c : loaded) {
                byId.put(c.getClientId(), c);
            }
        } catch (IOException e) {
            log.warn("Failed to load MCP OAuth client registry from {}", xml.getFile(), e);
        }
    }

    @Override
    public void save() {
        if (BulkChange.contains(this)) return;
        try {
            file().write(new ArrayList<>(byId.values()));
        } catch (IOException e) {
            log.warn("Failed to persist MCP OAuth client registry", e);
        }
    }
}
