package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import messages.ABCast;
import messages.BroadcastMessage;
import messages.ClusterInfoMessage;
import messages.Message;
import org.slf4j.Logger;

import java.time.Duration;

public class ClientActor extends AbstractBehavior<Message> {
    private ActorRef replicator;
    private int seq;
    private boolean shouldContinue;

    public ClientActor(ActorContext<Message> context) {
        super(context);
    }

    static Behavior<Message> create() {
        return Behaviors.setup(context ->
                new ClientActor(context));
    }

    @Override
    public Receive<Message> createReceive() {
        return newReceiveBuilder()
                .onMessage(ClusterInfoMessage.class, this::getClusterInfo)
                .onMessage(BroadcastMessage.class, this::orderedMessage)
                .build();
    }

    private Behavior<Message> orderedMessage(BroadcastMessage abCast){
        log().debug("ABCAST:{}",abCast.getData());

        getContext().getSystem().scheduler()
                .scheduleOnce(Duration.ofSeconds(1),
                        () -> {
                            if (shouldContinue && BroadcastActor.me == abCast.getSrc()) {
                                seq++;

                                replicator.tell(new ABCast<>("p" + BroadcastActor.me + "-seq" + seq + ":" + getContext().getSystem().address().hostPort()));
                            }
                        },getContext().getExecutionContext());

        return Behaviors.same();
    }

    private Behavior<Message> getClusterInfo(ClusterInfoMessage info){

//      Inicia o broadcast caso o estado do Cluster esteja OK
        if (info.isReady()){
           log().debug("Pronto para iniciar testes!!");
           replicator = info.replyTo;
           replicator.tell(new ABCast<>("p"+BroadcastActor.me+"-seq"+seq+":"+getContext().getSystem().address().hostPort()));
           shouldContinue = true;

           scheduleStop();
        }

        return Behaviors.same();
    }

    private Logger log() {
        return getContext().getLog();
    }

    private void scheduleStop(){
        getContext().getSystem().scheduler()
                .scheduleOnce(Duration.ofMinutes(5),
        () -> {
            shouldContinue = false;
        }, getContext().getExecutionContext());
    }
}
