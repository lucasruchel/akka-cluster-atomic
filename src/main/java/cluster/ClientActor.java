package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import messages.ABCast;
import messages.BroadcastMessage;
import messages.ClusterInfoMessage;
import messages.Message;
import org.slf4j.Logger;
import utils.AddressComparator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ClientActor extends AbstractBehavior<Message> {
    private boolean shouldStop;
    private int mCounter;
    private ActorRef abCastActor;
    private int id;
    private String bData;
    private ActorRef<Message> replicatorInstance;

    static final ServiceKey<Message> replicatorServiceKey = ServiceKey.create(Message.class, BroadcastActor.class.getSimpleName());

    public ClientActor(ActorContext<Message> context, int id) {
        super(context);

        this.shouldStop = false;
        mCounter = 0;
        abCastActor = null;
        this.id = id;

//        Tamanho inicial dos array que vai ser criado
        this.bData = null;
        this.replicatorInstance = null;

        receptionistSubscribe(context);
    }

    static Behavior<Message> create(int id) {
        return Behaviors.setup(context ->
                new ClientActor(context, id));
    }

    private void receptionistSubscribe(ActorContext<Message> context) {
        final ActorRef<Receptionist.Listing> listingActorRef = context.messageAdapter(Receptionist.Listing.class, Listeners::new);

        context.getSystem().receptionist()
                .tell(Receptionist.subscribe(replicatorServiceKey, listingActorRef));
    }

    @Override
    public Receive<Message> createReceive() {
        return newReceiveBuilder()
                .onMessage(BroadcastMessage.class, this::orderedMessage)
                .onMessage(ReplyRegister.class, this::registred)
                .onMessage(Listeners.class, this::onListeners)
                .build();
    }

    private Behavior<Message> registred(ReplyRegister m) {
        if (m.registered)
            createSchedule(Duration.ofMinutes(1),
                () -> {
                    String data = String.format("%s-c%s:%s",getContext().getSystem().address().toString(),id,++mCounter);
                    bData = data;

                    replicatorInstance.tell(new ABCast<>(data));
                    scheduleStop();

//                    log().info("SEND client:{} to replicator:{} data {}",
//                            getContext().getSelf().path(), replicatorInstance.path(),data);
                });


        return Behaviors.same();
    }

    private Behavior<Message> orderedMessage(BroadcastMessage abCast){
//            Exibe o log de que uma nova mensagem foi recebida
        log().info("ABCast:{}", abCast.getData());

//        Verifica se ainda é necessário executar mais rodadas e se o dado recebi foi enviado por este processo
        if (replicatorInstance != null && !shouldStop && bData.equals(abCast.getData())) {

//            Cria o novo dado a ser enviado
            String data = String.format("%s-c%s:%s",getContext().getSystem().address().toString(),id,++mCounter);
            bData = data;

//            Envia a instancia do replicador a requisição
            replicatorInstance.tell(new ABCast<>(data));

//            log().info("SEND client:{} to replicator:{} data {}",
//                    getContext().getSelf().path(), replicatorInstance.path(),data);
        }

        return Behaviors.same();
    }

    private Behavior<Message> onListeners(Listeners listeners){
        var instances = listeners.listing.getServiceInstances(replicatorServiceKey);

        var np = BroadcastActor.NUM_PROCESS;
        if (instances.size() == np){
            List<ActorRef<Message>> serviceInstances = instances.stream().collect(Collectors.toList());

            log().debug("Client {} - replicator instances {}",getContext().getSelf().path(), serviceInstances.size());
            serviceInstances.sort(new AddressComparator(getContext()));

//          Lista circular que obtem o
            replicatorInstance = serviceInstances.get(id % np);

//          Se o número de instancias for igual ao número de replicas de
//          replicação envia uma mensagem a um dos processos do VCube para registrar o cliente
            replicatorInstance.tell(new RequestRegister(getContext().getSelf()));

            log().debug("Registering to {}", replicatorInstance.path());
        }
        return Behaviors.same();
    }

    private void createSchedule(Duration tempo, Runnable run){
        getContext().getSystem()
                .scheduler()
                .scheduleOnce(tempo,run,getContext().getExecutionContext());
    }

    private void scheduleStop(){
        createSchedule(Duration.ofMinutes(5),
                        () -> shouldStop = true);
    }

    private Logger log() {
        return getContext().getLog();
    }

    private static class Listeners implements Message {
        final Receptionist.Listing listing;

        private Listeners(Receptionist.Listing listing) {
            this.listing = listing;
        }
    }
}
