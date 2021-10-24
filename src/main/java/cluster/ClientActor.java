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
    private boolean shouldStop;
    private int mCounter;
    private ActorRef abCastActor;

    public ClientActor(ActorContext<Message> context) {
        super(context);

        this.shouldStop = false;
        mCounter = 0;
        abCastActor = null;
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
        log().info("ABCast:{}", abCast.getData());

        if (abCastActor != null && BroadcastActor.me == abCast.getSrc() && !shouldStop) {
            createSchedule(Duration.ofSeconds(1),
                    () -> abCastActor.tell(new ABCast<String>(String.format("p%s:%s",BroadcastActor.me,++mCounter))));

        }


        return Behaviors.same();
    }

    private void createSchedule(Duration tempo, Runnable run){
        getContext().getSystem()
                .scheduler()
                .scheduleOnce(tempo,run,getContext().getExecutionContext());
    }

    private Behavior<Message> getClusterInfo(ClusterInfoMessage info){

//      Inicia o broadcast caso o estado do Cluster esteja OK
        if (info.isReady()){
            abCastActor = info.replyTo;
            abCastActor.tell(new ABCast<>(String.format("p%s:%s",BroadcastActor.me,++mCounter)));
            scheduleStop();
        }

        return Behaviors.same();
    }

    private void scheduleStop(){
        createSchedule(Duration.ofMinutes(5),
                        () -> shouldStop = true);
    }

    private Logger log() {
        return getContext().getLog();
    }
}
