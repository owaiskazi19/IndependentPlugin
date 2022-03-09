import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.opensearch.Version;
import org.opensearch.cluster.coordination.DeterministicTaskQueue;
import org.opensearch.cluster.coordination.FollowersChecker;
import org.opensearch.cluster.coordination.NodeHealthCheckFailureException;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.common.settings.Settings;
import org.opensearch.monitor.NodeHealthService;
import org.opensearch.monitor.StatusInfo;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.transport.MockTransport;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportService;
import com.carrotsearch.randomizedtesting.RandomizedRunner;


import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.equalTo;
import static org.opensearch.cluster.coordination.FollowersChecker.*;
import static org.opensearch.monitor.StatusInfo.Status.HEALTHY;
import static org.opensearch.node.Node.NODE_NAME_SETTING;

@RunWith(RandomizedRunner.class)
public class TestTransportRequest extends OpenSearchTestCase {
    @Before
    public void setupNew() throws Exception {
        System.out.println("hello");
    }

    @After
    public void setupNewAfter() throws Exception {
        System.out.println("hello");
    }


    private static Settings randomSettings() {
        final Settings.Builder settingsBuilder = Settings.builder();
        if (randomBoolean()) {
            settingsBuilder.put(FOLLOWER_CHECK_RETRY_COUNT_SETTING.getKey(), randomIntBetween(1, 10));
        }
        if (random().nextBoolean()) {
            settingsBuilder.put(FOLLOWER_CHECK_INTERVAL_SETTING.getKey(), randomIntBetween(100, 100000) + "ms");
        }
        if (random().nextBoolean()) {
            settingsBuilder.put(FOLLOWER_CHECK_TIMEOUT_SETTING.getKey(), randomIntBetween(1, 100000) + "ms");
        }
        return settingsBuilder.build();
    }

    // Need to add @Test for all the test
    //@Test
    public void testFailsNodeThatIsUnhealthy() {
        testTransportRequest(
                randomSettings(),
                () -> { throw new NodeHealthCheckFailureException("non writable exception"); },
                "health check failed",
                0,
                () -> new StatusInfo(HEALTHY, "healthy-info")
        );
    }


    private void testTransportRequest(
            Settings testSettings,
            Supplier<TransportResponse.Empty> responder,
            String failureReason,
            long expectedFailureTime,
            NodeHealthService nodeHealthService
    ) {
        final DiscoveryNode localNode = new DiscoveryNode("local-node", buildNewFakeTransportAddress(), Version.CURRENT);
        final DiscoveryNode otherNode = new DiscoveryNode("other-node", buildNewFakeTransportAddress(), Version.CURRENT);
        final Settings settings = Settings.builder().put(NODE_NAME_SETTING.getKey(), localNode.getName()).put(testSettings).build();
        final DeterministicTaskQueue deterministicTaskQueue = new DeterministicTaskQueue(settings, random());

        final MockTransport mockTransport = new MockTransport() {
            @Override
            protected void onSendRequest(long requestId, String action, TransportRequest request, DiscoveryNode node) {
                assertNotEquals(node, localNode);
                deterministicTaskQueue.scheduleNow(new Runnable() {
                    @Override
                    public void run() {
                        if (node.equals(otherNode) == false) {
                            // other nodes are ok
                            handleResponse(requestId, TransportResponse.Empty.INSTANCE);
                            return;
                        }
                        try {
                            final TransportResponse.Empty response = responder.get();
                            if (response != null) {
                                handleResponse(requestId, response);
                            }
                        } catch (Exception e) {
                            handleRemoteError(requestId, e);
                        }
                    }

                    @Override
                    public String toString() {
                        return "sending response to [" + action + "][" + requestId + "] from " + node;
                    }
                });
            }
        };

        final TransportService transportService = mockTransport.createTransportService(
                settings,
                deterministicTaskQueue.getThreadPool(),
                TransportService.NOOP_TRANSPORT_INTERCEPTOR,
                boundTransportAddress -> localNode,
                null,
                emptySet()
        );

        transportService.start();
        transportService.acceptIncomingRequests();

        final AtomicBoolean nodeFailed = new AtomicBoolean();

        final FollowersChecker followersChecker = new FollowersChecker(
                settings,
                transportService,
                fcr -> { assert false : fcr; },
                (node, reason) -> {
                    assertTrue(nodeFailed.compareAndSet(false, true));
                    MatcherAssert.assertThat(reason, equalTo(failureReason));
                },
                nodeHealthService
        );

        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder().add(localNode).add(otherNode).localNodeId(localNode.getId()).build();
        followersChecker.setCurrentNodes(discoveryNodes);
        while (nodeFailed.get() == false) {
            if (deterministicTaskQueue.hasRunnableTasks() == false) {
                deterministicTaskQueue.advanceTime();
            }
            deterministicTaskQueue.runAllRunnableTasks();
        }
        MatcherAssert.assertThat(deterministicTaskQueue.getCurrentTimeMillis(), equalTo(expectedFailureTime));

    }

}
