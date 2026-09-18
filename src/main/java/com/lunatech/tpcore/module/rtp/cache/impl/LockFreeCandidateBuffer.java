package com.lunatech.tpcore.module.rtp.cache.impl;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free, zero-GC Dmitry Vyukov MPMC ring buffer for 64-bit primitive long packed coordinates.
 * Guarantees strict linearizable single-slot ownership and thread-safety for high-concurrency producers and consumers.
 */
public final class LockFreeCandidateBuffer {

    public static final long EMPTY_SENTINEL = Long.MIN_VALUE;

    private final AtomicLongArray buffer;
    private final AtomicLongArray sequence;
    private final int mask;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    public LockFreeCandidateBuffer(int capacity) {
        int actualCapacity = 1;
        while (actualCapacity < capacity) {
            actualCapacity <<= 1;
        }
        this.buffer = new AtomicLongArray(actualCapacity);
        this.sequence = new AtomicLongArray(actualCapacity);
        this.mask = actualCapacity - 1;
        clear();
    }

    public boolean offer(long packedLocation) {
        if (packedLocation == EMPTY_SENTINEL) {
            return false;
        }

        long pos;
        while (true) {
            pos = tail.get();
            int slot = (int) (pos & mask);
            long seq = sequence.get(slot);
            long dif = seq - pos;

            if (dif == 0) {
                if (tail.compareAndSet(pos, pos + 1)) {
                    buffer.set(slot, packedLocation);
                    sequence.set(slot, pos + 1);
                    return true;
                }
            } else if (dif < 0) {
                return false;
            }
            Thread.onSpinWait();
        }
    }

    public long poll() {
        long pos;
        while (true) {
            pos = head.get();
            int slot = (int) (pos & mask);
            long seq = sequence.get(slot);
            long dif = seq - (pos + 1);

            if (dif == 0) {
                if (head.compareAndSet(pos, pos + 1)) {
                    long location = buffer.get(slot);
                    sequence.set(slot, pos + mask + 1);
                    return location;
                }
            } else if (dif < 0) {
                return EMPTY_SENTINEL;
            }
            Thread.onSpinWait();
        }
    }

    public long peek() {
        long pos = head.get();
        int slot = (int) (pos & mask);
        long seq = sequence.get(slot);
        long dif = seq - (pos + 1);
        if (dif == 0) {
            return buffer.get(slot);
        }
        return EMPTY_SENTINEL;
    }

    public int size() {
        return (int) Math.max(0, tail.get() - head.get());
    }

    public int capacity() {
        return mask + 1;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public void clear() {
        head.set(0);
        tail.set(0);
        for (int i = 0; i < buffer.length(); i++) {
            buffer.set(i, EMPTY_SENTINEL);
            sequence.set(i, i);
        }
    }
}
