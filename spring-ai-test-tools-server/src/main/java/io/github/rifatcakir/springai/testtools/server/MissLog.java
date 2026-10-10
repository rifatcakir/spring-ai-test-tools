package io.github.rifatcakir.springai.testtools.server;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.util.Assert;

/**
 * The most recent client-API {@code 404}s per namespace, newest first (PRD 6.3).
 *
 * <p>A {@code REPLAY_ONLY} failure surfaces in the application, often in a deployed
 * environment where nobody is reading its logs; this lets someone ask the server instead
 * which hashes were looked up and not found, and when. Bounded and in memory on purpose:
 * it is a debugging aid, not an audit trail, and a server restart starting with an empty
 * list costs nothing.
 *
 * @author Rifat Cakir
 */
public class MissLog {

	private final int capacity;

	private final Clock clock;

	private final Map<String, Deque<Miss>> misses = new ConcurrentHashMap<>();

	public MissLog(int capacity) {
		this(capacity, Clock.systemUTC());
	}

	MissLog(int capacity, Clock clock) {
		Assert.isTrue(capacity > 0, "capacity must be positive");
		this.capacity = capacity;
		this.clock = clock;
	}

	public void record(String namespace, FixtureKind kind, String hash) {
		Deque<Miss> deque = this.misses.computeIfAbsent(namespace, key -> new ArrayDeque<>());
		synchronized (deque) {
			deque.addFirst(new Miss(this.clock.instant(), kind, hash));
			while (deque.size() > this.capacity) {
				deque.removeLast();
			}
		}
	}

	/**
	 * Misses in {@code namespace}, newest first.
	 */
	public List<Miss> recent(String namespace) {
		Deque<Miss> deque = this.misses.get(namespace);
		if (deque == null) {
			return List.of();
		}
		synchronized (deque) {
			return new ArrayList<>(deque);
		}
	}

	/**
	 * One {@code 404}: when, and which fixture was asked for.
	 */
	public record Miss(Instant at, FixtureKind kind, String hash) {
	}

}
