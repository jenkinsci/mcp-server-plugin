/*
 * The MIT License
 *
 * Copyright (c) 2026, Fernando Celmer.
 */
package io.jenkins.plugins.mcp.server.auth;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.User;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.security.ApiTokenProperty;
import jenkins.util.SystemProperties;
import lombok.extern.slf4j.Slf4j;

@Extension
@Slf4j
public class TempTokenReaper extends AsyncPeriodicWork {

    private static final String INTERVAL_PROP = TempTokenReaper.class.getName() + ".intervalMinutes";

    public TempTokenReaper() {
        super("MCP temp API token reaper");
    }

    @Override
    public long getRecurrencePeriod() {
        long minutes = SystemProperties.getLong(INTERVAL_PROP, 15L);
        if (minutes < 1L) {
            minutes = 1L;
        }
        return TimeUnit.MINUTES.toMillis(minutes);
    }

    @Override
    protected void execute(TaskListener listener) {
        long now = Instant.now().getEpochSecond();
        TokenLedger ledger = TokenLedger.get();
        List<IssuanceRecord> all = ledger.list();
        int revoked = 0;
        for (IssuanceRecord r : all) {
            if (r.getExpiresAtEpoch() > now) {
                continue;
            }
            User u = User.getById(r.getUserId(), false);
            if (u == null) {
                ledger.remove(r.getTokenUuid());
                continue;
            }
            ApiTokenProperty prop = u.getProperty(ApiTokenProperty.class);
            if (prop == null) {
                ledger.remove(r.getTokenUuid());
                continue;
            }
            try {
                prop.getTokenStore().revokeToken(r.getTokenUuid());
                u.save();
                revoked++;
                log.info(
                        "MCP temp token revoked: user={} tokenName={} expiredAt={}",
                        r.getUserId(),
                        r.getTokenName(),
                        r.getExpiresAtEpoch());
            } catch (Exception e) {
                log.warn("Failed to revoke MCP temp token {} for user {}", r.getTokenName(), r.getUserId(), e);
            } finally {
                ledger.remove(r.getTokenUuid());
            }
        }
        if (revoked > 0) {
            listener.getLogger().println("MCP temp token reaper revoked " + revoked + " expired token(s)");
        }
    }
}
