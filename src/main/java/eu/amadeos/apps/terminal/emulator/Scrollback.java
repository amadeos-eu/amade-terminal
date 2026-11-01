package eu.amadeos.apps.terminal.emulator;

/** The lines that scrolled off the top of the main screen, oldest first, in a ring of limited size. */
public final class Scrollback {
    private Line[] ring;
    private int start;
    private int size;
    private int capacity;

    public Scrollback(int capacity) {
        this.capacity = Math.max(0, capacity);
        ring = new Line[Math.min(this.capacity, 1024)];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    /** Line {@code i}, 0 being the oldest. */
    public Line get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i + " of " + size);
        return ring[(start + i) % ring.length];
    }

    /** Adds a line at the end; returns true if the oldest line had to go for it. */
    public boolean add(Line line) {
        if (capacity == 0) return true;
        if (size == ring.length && ring.length < capacity) grow();
        if (size < ring.length) {
            ring[(start + size) % ring.length] = line;
            size++;
            return false;
        }
        ring[start] = line;
        start = (start + 1) % ring.length;
        return true;
    }

    /** Takes the newest line away (when a taller window pulls lines back onto the screen). */
    public Line removeLast() {
        if (size == 0) return null;
        int i = (start + size - 1) % ring.length;
        Line l = ring[i];
        ring[i] = null;
        size--;
        return l;
    }

    private void grow() {
        int n = (int) Math.min(capacity, Math.max(16L, ring.length * 2L));
        Line[] bigger = new Line[n];
        for (int i = 0; i < size; i++) bigger[i] = ring[(start + i) % ring.length];
        ring = bigger;
        start = 0;
    }

    public void clear() {
        ring = new Line[Math.min(capacity, 1024)];
        start = 0;
        size = 0;
    }

    /** A new maximum; the oldest lines beyond it are dropped. Returns how many were dropped. */
    public int setCapacity(int newCapacity) {
        newCapacity = Math.max(0, newCapacity);
        int keep = Math.min(size, newCapacity);
        int dropped = size - keep;
        Line[] lines = new Line[Math.max(Math.min(newCapacity, 1024), keep)];
        for (int i = 0; i < keep; i++) lines[i] = get(size - keep + i);
        ring = lines;
        start = 0;
        size = keep;
        capacity = newCapacity;
        return dropped;
    }
}
