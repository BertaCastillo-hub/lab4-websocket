@file:Suppress("NoWildcardImports")

package websockets

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.websocket.ClientEndpoint
import jakarta.websocket.ContainerProvider
import jakarta.websocket.OnMessage
import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.util.concurrent.CountDownLatch

private val logger = KotlinLogging.logger {}

@SpringBootTest(webEnvironment = RANDOM_PORT)
class ElizaServerTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun onOpen() {
        logger.info { "This is the test worker" }
        val latch = CountDownLatch(3)
        val list = mutableListOf<String>()

        val client = SimpleClient(list, latch)
        client.connect("ws://localhost:$port/eliza")
        latch.await()
        assertEquals(3, list.size)
        assertEquals("The doctor is in.", list[0])
    }

    @Test
    fun onChat() {
        logger.info { "Test thread" }
        // We wait for 4 messages: the 3 greeting lines ("The doctor is in.", "What's on your mind?", "---")
        // plus the doctor's reply to our sentence. The final "---" after the reply is NOT counted.
        val latch = CountDownLatch(4)
        val list = mutableListOf<String>()

        val client = ComplexClient(list, latch)
        // Messages are received on a WebSocket client thread, not on this test thread.
        // The thread names appear between [ ] in the test logs (build/test-results).
        client.connect("ws://localhost:$port/eliza")
        // The test thread blocks here until the client thread has counted down 4 times
        // (until we receive the answer after the greeting).
        latch.await()
        val size = list.size
        // 1. Why is size = list.size necessary?
        //    The client thread keeps adding messages after the latch opens (the final "---").
        //    Reading list.size once gives a single snapshot, so the assertion checks a stable value.
        //    The latch reaches 0 between the reply and the final "---": the test thread wakes up while
        //    the client thread may still be processing that "---". With messages still in flight the
        //    size cannot be predicted beforehand, so we freeze it in a local value and check that
        //    value.
        assertTrue(size in 4..5, "Expected 4 or 5 messages but got $size: $list")
        // 3. Why cannot assertEquals be used and why should we check the interval?
        //    The latch only waits for 4 messages (3 greeting lines + the doctor reply). The "---" after
        //    the reply may or may not have arrived when the test reads the list: it depends on thread
        //    scheduling and machine speed, given that the test thread and client thread are running
        //    in parallel. Both 4 and 5 are correct, so an exact value would be flaky.
        //    assertEquals(4, size) would pass on some machines and fail on others (OS, number of CPUs,
        //    scheduler). The interval 4..5 describes every valid final state: at least the 4 awaited
        //    messages and at most one more because we send a single sentence (only one extra "---").

        // 4. Index 3 is always the doctor's reply: the server sends the greeting before reading our
        //    sentence, and messages on one WebSocket connection arrive in order. The expected text is
        //    fixed because "always" has a single possible answer in Eliza, there is no randomness.
        assertEquals("Can you think of a specific example?", list[3])
    }
}

@ClientEndpoint
class SimpleClient(
    private val list: MutableList<String>,
    private val latch: CountDownLatch,
) {
    @OnMessage
    fun onMessage(message: String) {
        logger.info { "Client received: $message" }
        list.add(message)
        latch.countDown()
    }
}

@ClientEndpoint
class ComplexClient(
    private val list: MutableList<String>,
    private val latch: CountDownLatch,
) {
    @OnMessage
    fun onMessage(
        message: String,
        session: Session,
    ) {
        logger.info { "Client received: $message" }
        list.add(message)
        latch.countDown()
        // The third message ends the greeting: only then we talk to the doctor.
        // 5. The condition list.size == 3 is true exactly once (the greeting "---"), so the sentence
        //    is sent once and only once. Checking only message == "---" would also match the "---"
        //    after the reply and send the sentence again, making the list grow beyond 5.
        // 6. The sentence contains "always", the first keyword Eliza checks, whose only answer is
        //    "Can you think of a specific example?". Because "always" is checked first, other words in
        //    the sentence cannot change the keyword, so the reply is always the same. Keywords with
        //    several answers ("yes", "no", "i am", "dream", ...) or sentences with no keyword get a
        //    random reply and would make the test unpredictable.
        //    sendTextSafe synchronizes the send, avoiding IllegalStateException from concurrent sends.
        if (list.size == 3) {
            session.basicRemote.sendTextSafe("I always enjoy Web Engineering lessons")
        }
    }
}

fun Any.connect(uri: String) {
    ContainerProvider.getWebSocketContainer().connectToServer(this, URI(uri))
}
