package cluster;

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
        log().debug("{} mensagem entregue: {}",getContext().getSystem().address().hostPort(),
                                               abCast.getData());

        return Behaviors.same();
    }

    private Behavior<Message> getClusterInfo(ClusterInfoMessage info){

//      Inicia o broadcast caso o estado do Cluster esteja OK
        if (info.isReady()){
           log().debug("Pronto para iniciar testes!!");
           info.replyTo.tell(new ABCast<String>("Teste: "+getContext().getSystem().address().hostPort()));

           if (BroadcastActor.me == 2)
           getContext().getSystem()
                   .scheduler()
                   .scheduleOnce(Duration.ofMinutes(2),
                           () -> {
                               info.replyTo.tell(new ABCast<>("Teste-2"));
                           },
                           getContext().getExecutionContext()
                   );
        }

        return Behaviors.same();
    }

    private Logger log() {
        return getContext().getLog();
    }
}
