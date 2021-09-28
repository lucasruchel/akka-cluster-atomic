package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;
import messages.ReachabilityChanged;
import org.slf4j.Logger;

public class ClusterEventListener extends AbstractBehavior<ClusterEvent.ClusterDomainEvent> {

    private Cluster cluster;
    private Logger log;

    private ActorRef parent;

    private ClusterEventListener(ActorContext<ClusterEvent.ClusterDomainEvent> context, ActorRef parent) {
        super(context);

        this.parent = parent;
        cluster = Cluster.get(context.getSystem());
        this.log = context.getLog();

        subscribeToClusterEvent();
    }

    static Behavior<ClusterEvent.ClusterDomainEvent> create(ActorRef parent){
        return Behaviors.setup(context -> new ClusterEventListener(context, parent));
    }


    private void subscribeToClusterEvent(){
        cluster.subscriptions()
                .tell(
                        Subscribe.create(getContext().getSelf(), ClusterEvent.ClusterDomainEvent.class)
                );
    }

    private Behavior<ClusterEvent.ClusterDomainEvent> memberEvent(ClusterEvent.ClusterDomainEvent event){
        log.debug("{}: Notifying {}!!",getContext().getSelf().path(), event);
        if (event instanceof ClusterEvent.ReachabilityChanged){
            parent.tell(new ReachabilityChanged());
        }
        return Behaviors.same();
    }

    @Override
    public Receive<ClusterEvent.ClusterDomainEvent> createReceive() {
        return newReceiveBuilder()
                .onMessage(ClusterEvent.ClusterDomainEvent.class, this::memberEvent)
                .build();
    }


}
