package messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class BroadcastMessage<D> implements Message {



    @JsonProperty("data")
    private D data;

    private int src;
    private int seq;

    @JsonCreator
    public BroadcastMessage(D data, int src, int seq) {
        this.data = data;
        this.src = src;
        this.seq = seq;
    }
    public  BroadcastMessage(BroadcastMessage<D> m){
        this.data = m.data;
        this.seq = m.seq;
        this.src = m.seq;
    }

    public D getData() {
        return data;
    }

    public void setData(D data) {
        this.data = data;
    }

    public int getSrc() {
        return src;
    }

    public void setSrc(int src) {
        this.src = src;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BroadcastMessage)) return false;
        BroadcastMessage<?> that = (BroadcastMessage<?>) o;
        return src == that.src && seq == that.seq && data.equals(that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(src, seq, data);
    }

    @Override
    public String toString() {
           return "a-broad{" +
                "data=" + data +
                ", src=" + src +
                ", seq=" + seq +
                '}';

    }



}
