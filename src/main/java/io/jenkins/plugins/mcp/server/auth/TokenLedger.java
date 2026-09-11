/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.auth;

import hudson.BulkChange;
import hudson.XmlFile;
import hudson.model.Saveable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TokenLedger implements Saveable {

    private static final String FILE_NAME = "mcp-temp-tokens.xml";

    private static volatile TokenLedger INSTANCE;

    private final ConcurrentMap<String, IssuanceRecord> byUuid = new ConcurrentHashMap<>();

    public static synchronized TokenLedger get() {
        if (INSTANCE == null) {
            INSTANCE = new TokenLedger();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    public void put(IssuanceRecord record) {
        byUuid.put(record.getTokenUuid(), record);
        save();
    }

    public IssuanceRecord remove(String tokenUuid) {
        IssuanceRecord r = byUuid.remove(tokenUuid);
        if (r != null) {
            save();
        }
        return r;
    }

    public List<IssuanceRecord> list() {
        return Collections.unmodifiableList(new ArrayList<>(byUuid.values()));
    }

    public IssuanceRecord findByTokenName(String tokenName) {
        for (IssuanceRecord r : byUuid.values()) {
            if (r.getTokenName().equals(tokenName)) {
                return r;
            }
        }
        return null;
    }

    private XmlFile file() {
        return new XmlFile(new File(Jenkins.get().getRootDir(), FILE_NAME));
    }

    @SuppressWarnings("unchecked")
    private void load() {
        XmlFile xml = file();
        if (!xml.exists()) {
            return;
        }
        try {
            List<IssuanceRecord> loaded = (List<IssuanceRecord>) xml.read();
            byUuid.clear();
            for (IssuanceRecord r : loaded) {
                byUuid.put(r.getTokenUuid(), r);
            }
        } catch (IOException e) {
            log.warn("Failed to load MCP token ledger from {}", xml.getFile(), e);
        }
    }

    @Override
    public void save() {
        if (BulkChange.contains(this)) {
            return;
        }
        try {
            file().write(new ArrayList<>(byUuid.values()));
        } catch (IOException e) {
            log.warn("Failed to persist MCP token ledger", e);
        }
    }
}
