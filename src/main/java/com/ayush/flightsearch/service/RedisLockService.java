package com.ayush.flightsearch.service;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis locks with a renewable lease.
 *
 * <p>A lock needs a short TTL so a crashed holder cannot block everyone forever, but a
 * short TTL also expires under a holder that is simply slow - and then two requests run
 * the same costly work at once. The lease resolves that: a background task keeps pushing
 * the TTL out while the holder is still working, so the TTL only ever measures "how long
 * since we last heard from the holder", not "how long the work is allowed to take".
 *
 * <p>Renewal is bounded. Without a cap, a permanently stuck holder would keep its lock
 * alive forever and the lock would stop being a safety mechanism at all.
 */
@Slf4j
@Service
public class RedisLockService {

    /** Compare-and-pexpire, atomic inside Redis. See scripts/extend-lock.lua. */
    private static final RedisScript<Long> EXTEND_LOCK_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/extend-lock.lua"), Long.class);

    /** Compare-and-delete, atomic inside Redis. See scripts/release-lock.lua. */
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/release-lock.lua"), Long.class);

    private static final Long SCRIPT_OK = 1L;

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService leaseScheduler;

    public RedisLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        AtomicInteger threadNumber = new AtomicInteger();
        this.leaseScheduler = Executors.newScheduledThreadPool(2, runnable -> {
            // Numbered, so the two pool threads are told apart in the log.
            Thread thread = new Thread(runnable, "lease-renewer-" + threadNumber.incrementAndGet());
            // Daemon: a pending renewal must never hold JVM shutdown open. Any lock
            // still held at shutdown is left to expire on its TTL.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Tries to take the lock. The returned lease renews the TTL in the background until
     * it is closed or the cap is reached, and releases the lock on close.
     *
     * @param ttl      how long the lock lives between renewals
     * @param maxLease hard ceiling on total renewal time - once passed, renewal stops
     *                 and the lock is allowed to expire even if the work is unfinished
     * @return the lease, or empty when another caller already holds the lock
     */
    public Optional<LockLease> tryAcquire(String lockKey, Duration ttl, Duration maxLease) {
        String token = UUID.randomUUID().toString();

        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl))) {
            return Optional.empty();
        }

        LockLease lease = new LockLease(lockKey, token, ttl, maxLease);
        lease.startRenewal();
        return Optional.of(lease);
    }

    @PreDestroy
    void stopScheduler() {
        leaseScheduler.shutdownNow();
    }

    /**
     * A held lock plus its background renewal. Close it (try-with-resources) to stop
     * renewing and release the lock; closing twice is a no-op.
     */
    public final class LockLease implements AutoCloseable {

        private final String lockKey;
        private final String token;
        private final Duration ttl;
        private final long leaseDeadlineNanos;

        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean renewing = new AtomicBoolean(true);
        /**
         * -- GETTER --
         *  True when this lease can no longer guarantee exclusivity - either a renewal
         *  found the lock had been taken over, or the lease cap stopped renewal and the
         *  lock is expiring. The WARN logged at the time says which of the two it was.
         */
        @Getter
        private volatile boolean exclusivityLost;
        private volatile ScheduledFuture<?> renewalTask;

        private LockLease(String lockKey, String token, Duration ttl, Duration maxLease) {
            this.lockKey = lockKey;
            this.token = token;
            this.ttl = ttl;
            this.leaseDeadlineNanos = System.nanoTime() + maxLease.toNanos();
        }

        /**
         * Renew at a third of the TTL so two consecutive renewals can fail - a blip, a
         * slow Redis - before the lock is actually at risk of expiring.
         */
        private void startRenewal() {
            long intervalMs = Math.max(1, ttl.toMillis() / 3);
            this.renewalTask = leaseScheduler.scheduleAtFixedRate(
                    this::renew, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            try {
                if (closed.get() || !renewing.get()) {
                    return;
                }

                if (System.nanoTime() >= leaseDeadlineNanos) {
                    // The cap ends our protection just as surely as a takeover does: the
                    // lock now expires on its own and anyone may pick it up, so the holder
                    // has to be told even though nobody has taken it yet.
                    exclusivityLost = true;
                    log.warn("Lease cap reached for lock '{}' - no longer extending, it expires within {}",
                            lockKey, ttl);
                    stopRenewing();
                    return;
                }

                Long extended = redisTemplate.execute(
                        EXTEND_LOCK_SCRIPT, List.of(lockKey), token, String.valueOf(ttl.toMillis()));

                if (SCRIPT_OK.equals(extended)) {
                    log.debug("Extended lock '{}' by {}", lockKey, ttl);
                } else {
                    exclusivityLost = true;
                    log.warn("Lock '{}' is no longer ours - stopping renewal, another request may be "
                            + "running the same work", lockKey);
                    stopRenewing();
                }
            } catch (Exception e) {
                // Must not escape: scheduleAtFixedRate cancels a repeating task permanently
                // the first time it throws, which would silently end the lease. Swallow and
                // let the next tick retry - two more ticks fit inside one TTL.
                log.warn("Could not extend lock '{}', retrying on the next tick", lockKey, e);
            }
        }

        private void stopRenewing() {
            renewing.set(false);
            ScheduledFuture<?> task = renewalTask;
            if (task != null) {
                task.cancel(false);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            stopRenewing();

            Long deleted = redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
            if (SCRIPT_OK.equals(deleted)) {
                log.debug("Released lock '{}'", lockKey);
            } else {
                log.warn("Lock '{}' was not ours to release - it had already expired or been taken over",
                        lockKey);
            }
        }
    }
}
