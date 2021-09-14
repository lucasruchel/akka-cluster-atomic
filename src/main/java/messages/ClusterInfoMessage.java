package messages;

import akka.actor.typed.ActorRef;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.io.Serializable;

public class ClusterInfoMessage implements Message {

    private static final long serialVersionUID = 2L;
    public ActorRef replyTo;
    private boolean ready;

    @JsonCreator
    public ClusterInfoMessage(ActorRef replyTo) {
        this.ready = true;
        this.replyTo = replyTo;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
