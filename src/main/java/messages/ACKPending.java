package messages;

import akka.actor.typed.ActorRef;
import data.Timestamp;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class ACKPending<D> implements Message {
    private static final long serialVersionUID = 4L;


    private Set<Timestamp> tsaggr;
    private int id;

    public ACKPending(int id, Set<Timestamp> tsaggr) {
        this.tsaggr = tsaggr;
        this.id = id;
    }

    public Set<Timestamp> getTsaggr() {
        return tsaggr;
    }

    public void setTsaggr(Set<Timestamp> tsaggr) {
        this.tsaggr = tsaggr;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ACKPending)) return false;
        ACKPending<?> that = (ACKPending<?>) o;
        return id == that.id && tsaggr.equals(that.tsaggr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tsaggr, id);
    }

    @Override
    public String toString() {
               return "ack-reply{" +
                "data=" + tsaggr +
                ", id=" + id +
                '}';

    }

}
