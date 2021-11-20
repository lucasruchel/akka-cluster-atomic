package cluster;

import akka.actor.typed.ActorRef;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import messages.Message;

public class RequestRegister implements Message {
    final ActorRef<Message> client;

    @JsonCreator
    public RequestRegister(ActorRef<Message> self) {
        client = self;
    }
}
class ReplyRegister implements Message {
    final boolean registered;

    @JsonCreator
    public ReplyRegister(boolean registered){
        this.registered = registered;
    }
}
