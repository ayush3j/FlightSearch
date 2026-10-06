package com.ayush.flightsearch.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * A Bloom filter guarding the cache/DB path against cache penetration: repeated
 * lookups for keys that don't exist and so can never be satisfied by L1 or L2, each
 * one falling all the way through to the database every single time. add() records
 * every key known to be real; mightContain() then answers, in O(hashCount) time and
 * with no I/O, whether a key could be one of those - false means "definitely not,
 * don't even bother checking the cache or the database"; true means "maybe - check
 * the real tiers", allowing for the filter's own designed-in false-positive rate.
 *
 * <p>Hand-rolled, no external Bloom filter library: a plain bit array (a long[], 64
 * bits packed per word - not even {@link java.util.BitSet}) plus Kirsch-Mitzenmacher
 * double hashing (see {@link #hash}) to derive as many hash functions as needed from
 * just two independently-behaving base hashes.
 *
 * <p>Never produces a false negative: an added key always answers mightContain()
 * true, for as long as this instance lives. There is no remove() - a classic Bloom
 * filter can't support deletion without extra bookkeeping (e.g. a counting variant),
 * which this class deliberately doesn't need: entries are facts about which sectors
 * exist, and a sector is not expected to stop existing.
 */
@Service
public class FlightBloomFilter {

    /**
     * Default sizing: comfortably more than this app's real key domain (a few hundred
     * source/destination sectors at most) at a conservative false-positive rate, so
     * the filter stays accurate as the route table grows. Tune both constants
     * together - see optimalBitSize()/optimalHashCount() - if the domain size changes
     * by an order of magnitude.
     */
    private static final int DEFAULT_EXPECTED_INSERTIONS = 10_000;
    private static final double DEFAULT_FALSE_POSITIVE_RATE = 0.01;

    /** Size of the bit array, in bits (m in the standard Bloom filter formulas). */
    private final int bitSize;

    /** Number of hash functions applied per key (k in the standard formulas). */
    private final int hashCount;

    /** The bit array itself: 64 bits packed per long word. */
    private final long[] bits;

    public FlightBloomFilter() {
        this(DEFAULT_EXPECTED_INSERTIONS, DEFAULT_FALSE_POSITIVE_RATE);
    }

    /**
     * Package-private: lets tests build a small filter (a few hundred bits) sized for
     * the numbers they actually insert, instead of the full-size default - fast, and
     * without an unrealistic excess of headroom masking a bug in the false-positive
     * rate.
     */
    FlightBloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be strictly between 0 and 1");
        }

        this.bitSize = optimalBitSize(expectedInsertions, falsePositiveRate);
        this.hashCount = optimalHashCount(bitSize, expectedInsertions);
        this.bits = new long[(bitSize + Long.SIZE - 1) / Long.SIZE];
    }

    /**
     * The filter's key format for a sector: normalized source:destination, no prefix -
     * deliberately different from FlightCacheService's "flight-search:SRC:DST"
     * cache-tier keys, since this filter answers a narrower question (does this
     * sector exist at all?) than the cache does (is this exact search result
     * currently cached?). Normalisation matches FlightCacheService's: uppercased, so
     * "del" and "DEL" resolve to the same entry, the same way the repository's
     * case-insensitive lookup treats them as one route.
     */
    public String buildKey(String source, String destination) {
        return normalize(source) + ":" + normalize(destination);
    }

    private static String normalize(String airportCode) {
        return airportCode == null ? "" : airportCode.trim().toUpperCase();
    }

    /** Records key as real: every one of its hashCount bits gets set. */
    public void add(String key) {
        for (int seed = 0; seed < hashCount; seed++) {
            setBit(bitIndex(key, seed));
        }
    }

    /**
     * false means key is definitely not one that was ever add()-ed - safe to skip the
     * cache and the database entirely. true means key probably was added, but could
     * be a false positive; never a false negative.
     */
    public boolean mightContain(String key) {
        for (int seed = 0; seed < hashCount; seed++) {
            if (!getBit(bitIndex(key, seed))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The seed-th (of hashCount) hash value for key. Kirsch-Mitzenmacher double
     * hashing: rather than hand-writing hashCount independent hash functions, derive
     * two base hashes - h1 via FNV-1a, h2 by running h1 through a SplitMix64-style
     * avalanche mix - and combine them as h1 + seed*h2. That combination has been
     * shown to work as well as truly independent hash functions for Bloom filter
     * purposes, for a fraction of the implementation effort.
     *
     * <p>Package-private so tests can check its determinism and spread directly,
     * independent of bitIndex()'s reduction into the bit array's range.
     */
    long hash(String key, int seed) {
        long h1 = fnv1a64(key);
        long h2 = mix64(h1);
        return h1 + (long) seed * h2;
    }

    private int bitIndex(String key, int seed) {
        long unsignedHash = hash(key, seed) & Long.MAX_VALUE; // clear the sign bit before the modulo
        return (int) (unsignedHash % bitSize);
    }

    private void setBit(int index) {
        // index is masked to its low 6 bits automatically by Java's shift operator
        // (JLS 15.19), so this needs no explicit "index % 64" - the same reason
        // index >>> 6 (a plain "/ 64") picks the matching word.
        bits[index >>> 6] |= 1L << index;
    }

    private boolean getBit(int index) {
        return (bits[index >>> 6] & (1L << index)) != 0;
    }

    /** FNV-1a, 64-bit variant: simple, fast, well-documented, and dependency-free. */
    private static long fnv1a64(String key) {
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }

    /**
     * SplitMix64's finalising mix step: a few xor-shift/multiply rounds that turn any
     * input into a well-avalanched output (a small change in the input flips roughly
     * half the output bits). Used here purely to derive a second, effectively
     * independent hash from the first - not for SplitMix64's usual PRNG role.
     */
    private static long mix64(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    /** m, the standard Bloom filter sizing formula: m = ceil(-n * ln(p) / ln(2)^2). */
    private static int optimalBitSize(int expectedInsertions, double falsePositiveRate) {
        double m = -(expectedInsertions * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2));
        return Math.max(1, (int) Math.ceil(m));
    }

    /** k, the standard Bloom filter sizing formula: k = round((m / n) * ln(2)). */
    private static int optimalHashCount(int bitSize, int expectedInsertions) {
        double k = ((double) bitSize / expectedInsertions) * Math.log(2);
        return Math.max(1, (int) Math.round(k));
    }

    /** The configured bit-array size, so tests don't hardcode a second copy of it. */
    int bitSize() {
        return bitSize;
    }

    /** The configured hash-function count, so tests don't hardcode a second copy of it. */
    int hashCount() {
        return hashCount;
    }
}
