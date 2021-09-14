package data;

import java.util.Objects;

public class Timestamp implements Comparable<Timestamp> {
    private int id;
    private int ts;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getTs() {
        return ts;
    }

    public void setTs(int ts) {
        this.ts = ts;
    }

    public Timestamp(int id, int ts) {
        this.id = id;
        this.ts = ts;
    }

    @Override
    public String toString() {
        return "{" +
                "id=" + id +
                ", ts=" + ts +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Timestamp)) return false;
        Timestamp timestamp = (Timestamp) o;
        return id == timestamp.id && ts == timestamp.ts;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ts);
    }

    @Override
    public int compareTo(Timestamp o) {
        if (this.ts != o.ts)
            return this.ts - o.ts;
        else
            return this.id - o.id;
    }
}
