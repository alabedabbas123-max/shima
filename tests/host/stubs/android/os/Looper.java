package android.os;
import java.util.Iterator;
import java.util.PriorityQueue;
/** Minimal deterministic main-loop test double, not an Android implementation. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private static final Thread MAIN_THREAD = Thread.currentThread();
    private final PriorityQueue<Entry> entries = new PriorityQueue<Entry>();
    private long sequence;
    static final class Entry implements Comparable<Entry> {
        final Handler owner; final Runnable runnable; final long when, sequence;
        Entry(Handler owner, Runnable runnable, long when, long sequence) { this.owner=owner; this.runnable=runnable; this.when=when; this.sequence=sequence; }
        public int compareTo(Entry other) { int c=Long.compare(when, other.when); return c != 0 ? c : Long.compare(sequence, other.sequence); }
    }
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return Thread.currentThread() == MAIN_THREAD ? MAIN : null; }
    synchronized void post(Handler owner, Runnable task, long delay) { entries.add(new Entry(owner, task, System.nanoTime()+delay*1_000_000L, sequence++)); }
    synchronized void remove(Handler owner, Runnable task) {
        Iterator<Entry> it=entries.iterator();
        while (it.hasNext()) { Entry e=it.next(); if (e.owner==owner && (task==null || task==e.runnable)) it.remove(); }
    }
    private synchronized Runnable poll() {
        Entry e=entries.peek();
        return e != null && e.when <= System.nanoTime() ? entries.remove().runnable : null;
    }
    public static void pump(long millis) throws InterruptedException {
        long end=System.nanoTime()+millis*1_000_000L;
        do {
            Runnable task;
            while ((task=MAIN.poll()) != null) task.run();
            Thread.sleep(2);
        } while (System.nanoTime() < end);
    }
}
