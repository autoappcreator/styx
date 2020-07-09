/*
  Copyright (C) 2013-2020 Expedia Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.hotels.styx.server;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.hotels.styx.api.LiveHttpRequest.get;
import static com.hotels.styx.api.Metrics.name;
import static com.hotels.styx.server.RequestStatsCollector.REQUEST_LATENCY;
import static com.hotels.styx.server.RequestStatsCollector.REQUEST_OUTSTANDING;
import static com.hotels.styx.server.RequestStatsCollector.REQUEST_RECEIVED;
import static com.hotels.styx.server.RequestStatsCollector.RESPONSE_SENT;
import static com.hotels.styx.server.RequestStatsCollector.RESPONSE_STATUS;
import static com.hotels.styx.server.RequestStatsCollector.STATUS_CLASS_TAG;
import static com.hotels.styx.server.RequestStatsCollector.STATUS_CLASS_UNRECOGNISED;
import static com.hotels.styx.server.RequestStatsCollector.STATUS_TAG;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

public class RequestStatsCollectorTest {

    static final String PREFIX = "test";

    MeterRegistry metrics;
    Object requestId = get("/requestId1").build().id();
    Object requestId2 = get("/requestId2").build().id();
    TestClock clock = new TestClock();
    RequestStatsCollector sink;

    @BeforeEach
    public void setUp() {
        metrics = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        clock.setNanoTime(0);
        sink = new RequestStatsCollector(metrics, PREFIX);
    }

    @Test
    public void maintainsOutstandingRequestsCount() {
        sink.onRequest(requestId);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onComplete(requestId, 200);
        assertThat(requestOutstandingValue(), is(0.0));
    }

    @Test
    public void ignoresAdditionalCallsToOnRequestWithSameRequestId() {
        sink.onRequest(requestId);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onRequest(requestId);
        assertThat(requestOutstandingValue(), is(1.0));
    }

    @Test
    public void maintainsOutstandingRequestsCountForSeveralSimultaneousRequests() {
        sink.onRequest(requestId);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onRequest(requestId2);
        assertThat(requestOutstandingValue(), is(2.0));

        sink.onComplete(requestId, 200);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onTerminate(requestId2);
        assertThat(requestOutstandingValue(), is(0.0));
    }

    @Test
    public void doesNotDecrementOutstandingRequestForUnknownRequestIds() {
        sink.onRequest(requestId);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onComplete(requestId2, 200);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onTerminate(requestId2);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onComplete(requestId, 200);
        assertThat(requestOutstandingValue(), is(0.0));
    }

    @Test
    public void decrementsOutstandingRequestCountWithOnTerminated() {
        sink.onRequest(requestId);
        assertThat(requestOutstandingValue(), is(1.0));

        sink.onTerminate(requestId);
        assertThat(requestOutstandingValue(), is(0.0));
    }

    @Test
    public void maintainsRequestLatencyTimer() {
        sink.onRequest(requestId);
        clock.setNanoTime(100, MILLISECONDS);
        sink.onComplete(requestId, 200);

        Timer timer = metrics.get(name(PREFIX, REQUEST_LATENCY)).timer();
        assertThat(timer.count(), is(1L));
        assertThat(timer.mean(MILLISECONDS), is(closeTo(100, 2)));
    }

    @Test
    public void maintainsRequestLatencyTimerForMultipleOngoingRequests() {
        sink.onRequest(requestId);
        sink.onRequest(requestId2);

        clock.setNanoTime(100, MILLISECONDS);

        sink.onComplete(requestId, 200);
        Timer timer = metrics.get(name(PREFIX, REQUEST_LATENCY)).timer();
        assertThat(timer.count(), is(1L));
        assertThat(timer.mean(MILLISECONDS), is(closeTo(100, 2)));

        clock.setNanoTime(200, MILLISECONDS);

        sink.onTerminate(requestId2);
        timer = metrics.get(name(PREFIX, REQUEST_LATENCY)).timer();
        assertThat(timer.count(), is(2L));
        assertThat(timer.mean(MILLISECONDS), is(closeTo(150, 2)));
    }

    @Test
    public void stopsLatencyTimerWhenConnectionResets() {
        sink.onRequest(requestId);
        clock.setNanoTime(100, MILLISECONDS);
        sink.onTerminate(requestId);

        Timer timer = metrics.get(name(PREFIX, REQUEST_LATENCY)).timer();
        assertThat(timer.count(), is(1L));
        assertThat(timer.mean(MILLISECONDS), is(closeTo(100, 2)));
    }

    @Test
    public void maintainsIncomingRequestRate() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 200);
        sink.onRequest(requestId);
        sink.onComplete(requestId, 200);

        assertThat(counterValue(REQUEST_RECEIVED, Tags.empty()), is(2.0));
    }

    @Test
    public void reports200ResponsesAs2xx() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 200);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "2xx")), is(1.0));
    }

    @Test
    public void reports201ResponsesAs2xx() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 201);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "2xx")), is(1.0));
    }

    @Test
    public void reports204ResponsesAs2xx() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 204);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "2xx")), is(1.0));
    }

    @Test
    public void reports400ResponsesAs4xx() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 400);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "4xx")), is(1.0));
    }

    @Test
    public void reports404ResponsesAs4xx() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 404);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "4xx")), is(1.0));
    }

    @Test
    public void reports500Responses() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 500);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "5xx").and(STATUS_TAG, "500")), is(1.0));
    }

    @Test
    public void reports504Responses() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 504);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "5xx").and(STATUS_TAG, "504")), is(1.0));
    }

    @Test
    public void reportsUnknownServerErrorCodesAs5xx() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 566);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, "5xx").and(STATUS_TAG, "566")), is(1.0));
    }

    @Test
    public void reportsUnrecognisedHttpSatusCodesLessThan100() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 99);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, STATUS_CLASS_UNRECOGNISED)), is(1.0));
    }

    @Test
    public void reportsUnrecognisedHttpSatusCodesGreaterThan599() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 600);
        assertThat(counterValue(RESPONSE_STATUS, Tags.of(STATUS_CLASS_TAG, STATUS_CLASS_UNRECOGNISED)), is(1.0));
    }

    @Test
    public void maintainsResponsesSentCount() {
        sink.onRequest(requestId);
        sink.onComplete(requestId, 200);
        assertThat(counterValue(RESPONSE_SENT, Tags.empty()), is(1.0));
    }

    private static final class TestClock implements Clock {
        private long nanoTime;

        public void setNanoTime(long nanoTime) {
            this.nanoTime = nanoTime;
        }

        public void setNanoTime(long time, TimeUnit timeUnit) {
            this.nanoTime = timeUnit.toNanos(time);
        }

        @Override
        public long wallTime() {
            return MILLISECONDS.convert(nanoTime, NANOSECONDS);
        }

        @Override
        public long monotonicTime() {
            return nanoTime;
        }
    }


    private double counterValue(String baseName, Tags tags) {
        return Optional.ofNullable(metrics.get(name(PREFIX, baseName))
                .tags(tags)
                .counter())
                .map(Counter::count)
                .orElse(0.0);
    }

    private double requestOutstandingValue() {
        return Optional.ofNullable(metrics.get(name(PREFIX, REQUEST_OUTSTANDING))
                .gauge())
                .map(Gauge::value)
                .orElse(0.0);
    }
}
