package com.lq.deepseek.gateway.singleflight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内 single-flight 语义验证（Redis 降级路径）。
 *
 * <p>这是治理层最核心的断言：并发 N 个相同请求，下游只能被真实调用 1 次，
 * 其余全部拿到同一份回放结果。
 */
class SingleFlightLocalTest {

    @Test
    @DisplayName("并发相同请求：下游只执行一次，其余命中结果回放")
    void shouldExecuteProducerOnlyOnce() throws Exception {
        LocalFlightRegistry registry = new LocalFlightRegistry();
        int concurrency = 8;
        AtomicInteger produced = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> modes = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        try {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, concurrency)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await(5, TimeUnit.SECONDS);
                        FlightOutcome outcome = registry.run("same-key", () -> {
                            produced.incrementAndGet();
                            sleep();
                            return "{\"payload\":\"x\"}";
                        }, 5_000L);
                        modes.add(outcome.mode());
                        assertEquals("{\"payload\":\"x\"}", outcome.payloadJson());
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, produced.get(), "相同输入只允许触发一次真实执行");
        assertEquals(1, modes.stream().filter(FlightOutcome.MODE_LOCAL_OWNER::equals).count());
        assertEquals(concurrency - 1,
                modes.stream().filter(FlightOutcome.MODE_LOCAL_REPLAY::equals).count());
        assertEquals(0, registry.inFlight(), "flight 结束后必须清理，避免内存泄漏");
    }

    @Test
    @DisplayName("不同输入互不影响：各自真实执行")
    void shouldNotShareDifferentKeys() {
        LocalFlightRegistry registry = new LocalFlightRegistry();
        AtomicInteger produced = new AtomicInteger();

        FlightOutcome first = registry.run("key-a", () -> {
            produced.incrementAndGet();
            return "a";
        }, 1_000L);
        FlightOutcome second = registry.run("key-b", () -> {
            produced.incrementAndGet();
            return "b";
        }, 1_000L);

        assertTrue(first.isOwner());
        assertTrue(second.isOwner());
        assertEquals(2, produced.get());
    }

    private void sleep() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
