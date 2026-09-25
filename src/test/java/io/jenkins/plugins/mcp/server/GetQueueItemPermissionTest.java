package io.jenkins.plugins.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.User;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.mcp.server.extensions.DefaultMcpServer;
import jenkins.model.Jenkins;
import jenkins.model.queue.QueueItem;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * getQueueItem must not expose a queue item for a job the caller cannot read.
 * Core hides that data behind Queue.Item#getApi() (Item.READ/DISCOVER on the task);
 * the MCP tool serializes the item directly, so it has to apply the same rule itself.
 */
@WithJenkins
class GetQueueItemPermissionTest {

    /** Park a job in the queue on an impossible label so it stays there, and return its id. */
    private long parkJobInQueue(JenkinsRule j, String name) throws Exception {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            FreeStyleProject p = j.createFreeStyleProject(name);
            p.setAssignedLabel(new LabelAtom("no-such-agent-exists"));
            p.scheduleBuild2(0);
            Queue.Item item = j.jenkins.getQueue().getItem(p);
            assertNotNull(item, "job should be parked in the queue");
            return item.getId();
        }
    }

    @Test
    void queueItemHiddenFromUserWithoutItemRead(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader")); // Overall/Read only, no Item.READ

        long id = parkJobInQueue(j, "secret-job");
        DefaultMcpServer server = ExtensionList.lookupSingleton(DefaultMcpServer.class);

        // A caller with only Overall/Read must not be able to read a queue item for a job
        // they cannot see. Before the fix, getQueueItem returns the item and leaks it.
        User reader = User.getById("reader", true);
        try (ACLContext ignored = ACL.as2(reader.impersonate2())) {
            assertThrows(
                    AccessDeniedException.class,
                    () -> server.getQueueItem(id),
                    "queue item for an unreadable job must not be exposed");
        }
    }

    @Test
    void queueItemVisibleToAdmin(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));

        long id = parkJobInQueue(j, "visible-job");
        DefaultMcpServer server = ExtensionList.lookupSingleton(DefaultMcpServer.class);

        User admin = User.getById("admin", true);
        try (ACLContext ignored = ACL.as2(admin.impersonate2())) {
            QueueItem item = server.getQueueItem(id);
            assertNotNull(item, "admin should still be able to read the queue item");
            assertEquals(id, item.getId());
        }
    }
}
