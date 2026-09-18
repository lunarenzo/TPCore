package com.lunatech.tpcore.module.rtp.cache.impl;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class LockFreeCandidateBuffer {

    private final AtomicLongArray buffer;
    private final int mask;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    public LockFreeCandidateBuffer(int capacity) {
        int actualCapacity = 1;
        while (actualCapacity < capacity) {
            actualCapacity <<= 1;
        }
        this.buffer = new AtomicLongArray(actualCapacity);
        this.mask = actualCapacity - 1;
    }

    public boolean offer(long packedLocation) {
        if (packedLocation == -1L) {
            return false;
        }
        long currentTail;
        long currentHead;
        do {
            currentTail = tail.get();
            currentHead = head.get();
            if (currentTail - currentHead >= buffer.length()) {
                return false;
            }
        } while (!tail.compareAndSet(currentTail, currentTail + 1));

        buffer.set((int) (currentTail & mask), packedLocation);
        return true;
    }

    public long poll() {
        long currentHead;
        long location;
        do {
            currentHead = head.get();
            if (currentHead >= tail.get()) {
                return -1L;
            }
            location = buffer.get((int) (currentHead & mask));
        } while (location == 0L || !head.compareAndSet(currentHead, currentHead + 1));

        buffer.set((int) (currentHead & mask), 0L);
        return location;
    }

    public long peek() {
        long currentHead = head.get();
        if (currentHead >= tail.get()) {
            return -1L;
        }
        return buffer.get((int) (currentHead & mask));
    }

    public int size() {
        return (int) (tail.get() - head.get());
    }

    public int capacity() {
        return mask + 1;
    }

    public boolean isEmpty() {
        return tail.get() == head.get();
    }

    public void clear() {
        while (poll() != -1L) {
            // Drain buffer
        }
    }
}
