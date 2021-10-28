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
import java.util.ArrayList;
import java.util.List;

public class ClientActor extends AbstractBehavior<Message> {
    private boolean shouldStop;
    private int mCounter;
    private ActorRef abCastActor;
    private int reqPerClient;
    private List<String> bData;

    public ClientActor(ActorContext<Message> context, int reqPerClient) {
        super(context);

        this.shouldStop = false;
        mCounter = 0;
        abCastActor = null;
        this.reqPerClient = reqPerClient;

//        Tamanho inicial dos array que vai ser criado
        this.bData = new ArrayList<>(reqPerClient);
        for (int i = 0; i < reqPerClient; i++) {
            bData.add(null);
        }

    }

    static Behavior<Message> create(int reqPerClient) {
        return Behaviors.setup(context ->
                new ClientActor(context, reqPerClient));
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
            for (int i = 0; i < reqPerClient; i++) {
                if (bData.get(i).equals(abCast.getData())){
                    String data = String.format("p%s:-c%s:%s",BroadcastActor.me, i,++mCounter);
                    bData.set(i,data);

                    abCastActor.tell(new ABCast<>(data));
                    break;
                }
            }

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
            for (int i = 0; i < reqPerClient; i++) {
                String data = String.format("p%s:-c%s:%s",BroadcastActor.me, i,++mCounter);
                bData.set(i,data);

                abCastActor.tell(new ABCast<>(data));
            }
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
