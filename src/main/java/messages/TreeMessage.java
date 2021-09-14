package messages;

import akka.actor.typed.ActorRef;

import data.Timestamp;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class TreeMessage<D> implements Message {

    private static final long serialVersionUID = 1L;



    public ActorRef<Message> replyTo;
    private TreeSet<Timestamp> tsaggr;
    private BroadcastMessage<D> data;
    private int from;

    public TreeMessage(ActorRef<Message> replyTo, BroadcastMessage<D> data, int from, TreeSet<Timestamp> tsaggr) {
        this.replyTo = replyTo;
        this.tsaggr = tsaggr;
        this.data = data;
        this.from = from;

    }


    public BroadcastMessage<D> getData() {
        return data;
    }

    public void setData(BroadcastMessage<D> data) {
        this.data = data;
    }

    public Set<Timestamp> getTsaggr() {
        return tsaggr;
    }

    public void setTsaggr(TreeSet<Timestamp> tsaggr) {
        this.tsaggr = tsaggr;
    }

    public int getFrom() {
        return from;
    }

    public void setFrom(int from) {
        this.from = from;
    }

    public int getMaxTimestamp(){
        return tsaggr.last().getTs();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TreeMessage)) return false;
        TreeMessage<?> that = (TreeMessage<?>) o;
        return this.from == that.from && tsaggr.equals(that.tsaggr) && data.equals(that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tsaggr, data, from);
    }

    @Override
    public String toString() {
            return "TREE{" +
                "tsaggr=" + tsaggr +
                ", data=" + data +
                ", from=" + from +
                '}';
    }

}
