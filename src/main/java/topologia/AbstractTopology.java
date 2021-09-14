package topologia;

import akka.actor.typed.ActorRef;
import cluster.BroadcastActor;
import com.google.common.collect.BiMap;
import messages.Message;

import java.util.List;

public abstract class AbstractTopology {


    protected BiMap<Integer, ActorRef<Message>> corrects;

    AbstractTopology(){

    }

    public BiMap<Integer, ActorRef<Message>> getCorrects() {
        return corrects;
    }

    public void setCorrects(BiMap<Integer, ActorRef<Message>> corrects) {
        this.corrects = corrects;
    }

    public abstract List<Integer> destinations(int me);
    public abstract List<Integer> destinations(int me, int source);

    public List<Integer> fathers(int p, int root){
        return null;
    }

    public int nextNeighboor(int me, int old){
        return -1;
    }
    public abstract List<Integer> neighborhood(int p, int h);


}
