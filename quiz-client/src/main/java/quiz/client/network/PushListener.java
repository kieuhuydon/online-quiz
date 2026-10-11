package quiz.client.network;

import quiz.common.protocol.Response;

/** Runs off the socket reader. UI subscribers must dispatch through FxAsync and check generation. */
@FunctionalInterface
public interface PushListener {
    void onPush(long generation, Response response);
}
