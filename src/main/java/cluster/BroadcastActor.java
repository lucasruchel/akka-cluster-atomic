package cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import messages.*;
import data.Timestamp;
import org.slf4j.Logger;
import topologia.VCubeTopology;
import utils.IPUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BroadcastActor extends AbstractBehavior<Message> {
  private List<ActorRef<Message>> serviceInstances;


  private static int NUM_PROCESS = 8;
  private boolean ready = false;

  BiMap<Integer, ActorRef<Message>> corretos = HashBiMap.create();

  private ConcurrentMap<BroadcastMessage<String>,Integer> stamped;
  private ConcurrentMap<BroadcastMessage<String>, TreeSet<Timestamp>> received;
  //    Mapa do conjunto de timestamps enviados a cada processo e o to, from de cada ACK
  private ConcurrentMap<TreeMessage<String>, ConcurrentMap<Integer,Integer>> pendingAck;
  private List<Integer> last_i;
  //    relógio lógico que identifica unicamente as mensagens enviadas por i
  private int lc;
  //    timestamp utilizado para ordem total
  private int ts;

  private VCubeTopology topo;

  static final ServiceKey<Message> serviceKey = ServiceKey.create(Message.class, BroadcastActor.class.getSimpleName());
  private int me;

//  Controla a quantidade de replicas prontas para iniciar a replicação
  private Set<ActorRef> n_ready;

  private ActorRef<Message> clientRef;

  static Behavior<Message> create(ActorRef<Message> actorRef) {
    return Behaviors.setup(context ->
            new BroadcastActor(context, actorRef)
    );
  }

  private BroadcastActor(ActorContext<Message> context, ActorRef<Message> actorRef) {
    super(context);

    this.clientRef = actorRef;

    stamped = new ConcurrentHashMap<>();
    received = new ConcurrentHashMap<>();
    pendingAck = new ConcurrentHashMap<>();

    n_ready = new HashSet<>(8);

    last_i = new ArrayList<>();
    for (int i = 0; i < NUM_PROCESS; i++) {
      last_i.add(-1);
    }

    // inicializa ts e lc
    ts = lc = 0;

    topo = new VCubeTopology(NUM_PROCESS);
    topo.setCorrects(corretos);


    receptionistRegisterSubscribe(context);
  }

  @Override
  public Receive<Message> createReceive() {
    return newReceiveBuilder()
        .onMessage(Listeners.class, this::onListeners)
        .onMessage(ABCast.class, this::broadcast)
        .onMessage(TreeMessage.class, this::receiveTree)
        .onMessage(ClusterInfoMessage.class, this::callClient)
        .onMessage(ACKPending.class, this::receiveACk)
        .build();
  }



  private Behavior<Message> receiveTree(TreeMessage tree){
    log().info("TREE recebido!!");

    ActorRef<Message> src = tree.replyTo;

//        Verifica se processo de origem está suspeito, caso estiver mod2==1, nenhuma ação é tomada
    if (!corretos.containsValue(src))
      return Behaviors.same();


    ts = Math.max(ts+1,tree.getMaxTimestamp());

    TreeSet<Timestamp> tsaggr = new TreeSet(tree.getTsaggr());

    BroadcastMessage<String> data = tree.getData();
    if (data.getSeq() > last_i.get(data.getSrc()) &&
            !received.containsKey(data) && !stamped.containsKey(data)){


//           Local timestamp
      Timestamp timestamp = new Timestamp(me,ts);
      tsaggr.add(timestamp);

      List<Integer> neighbors = topo.neighborhood(me,topo.log2(NUM_PROCESS));
      List<Integer> subtree = topo.neighborhood(me,topo.cluster(me,corretos.inverse().get(src)) - 1);

      // diferenla entre as duas listas, remove todas os processos na árvore do processo de origem
      neighbors.removeAll(subtree);
      for (int i: neighbors) {
        TreeSet<Timestamp> re_ts = new TreeSet<>();
        re_ts.add(timestamp);

        TreeMessage<String> replay = new TreeMessage(getSelf(), data,me,re_ts);


        send(corretos.get(i),replay);
        addAck(me,i,replay);
      }
    }



//      Adiciona todos os timestamps de tree recebidos
//        Verificar se a mensagem já não foi entregue, assim é evitado que seja aguardado a entrega desta mensagem, mesmo já tendo sido marcada
    if (last_i.get(data.getSrc()) < data.getSeq()){
      if (received.get(data) != null){
        received.get(data).addAll(tsaggr);
      } else {
        received.put(data,new TreeSet<>(tsaggr));
      }
    }
//      Encaminha os processos à árvore do processo de origem
    TreeMessage<String> fwd = new TreeMessage<String>(getSelf(), data,corretos.inverse().get(src),tsaggr);

//        Cluster em que processo está
    int s = topo.cluster(me,corretos.inverse().get(src)) - 1;
    forward(fwd, s);
    checkDeliverable(data);

    if (s > 0)
      checkAcks(corretos.inverse().get(src),fwd);
    else
      checkAcks(corretos.inverse().get(src),tree);

    return Behaviors.same();
  }

  private ActorRef<Message> getSelf(){
    return getContext().getSelf();
  }


  private void receptionistRegisterSubscribe(ActorContext<Message> context) {
    final ActorRef<Receptionist.Listing> listingActorRef = context.messageAdapter(Receptionist.Listing.class, Listeners::new);

    context.getSystem().receptionist()
        .tell(Receptionist.register(serviceKey, context.getSelf()));
    context.getSystem().receptionist()
        .tell(Receptionist.subscribe(serviceKey, listingActorRef));
  }

  private Behavior<Message> onListeners(Listeners listeners) {
    var instances = listeners.listing.getServiceInstances(serviceKey);


    log().info("Cluster aware actors subscribers changed, count {}", instances.size());

//    instances.forEach(actorRef -> log().info("## REF ## {}",actorRef.path().address()));

    serviceInstances = instances.stream().collect(Collectors.toList());
    Comparator<ActorRef<Message>> c = (a, b) -> {
      String addr_a;
      if (a.equals(getContext().getSelf()))
        addr_a = getContext().getSystem().address().hostPort();
      else
        addr_a = a.path().address().hostPort();

      String addr_b;
      if (b.equals(getContext().getSelf()))
        addr_b = getContext().getSystem().address().hostPort();
      else
        addr_b = b.path().address().hostPort();

      if (!IPUtils.getIP(addr_a).equals(IPUtils.getIP(addr_b)))
        return IPUtils.getIP(addr_a).compareTo(IPUtils.getIP(addr_b));
      else
        return IPUtils.getPort(addr_a) - IPUtils.getPort(addr_b);
    };

    serviceInstances.sort(c);

    if (serviceInstances.size() == NUM_PROCESS && !ready){
      ready = true;

      serviceInstances.forEach(new Consumer<>() {
        int i = 0;

        @Override
        public void accept(ActorRef<Message> actor) {

          if (actor.equals(getContext().getSelf())) {
            me = i;

          }
          corretos.put(i, actor);

//          Informa a todos os atores de que está replica está com os nós que deveria
          actor.tell(new ClusterInfoMessage(getSelf()));

          i++;
        }
      });


    }
    return Behaviors.same();
  }

  private Behavior<Message> callClient(ClusterInfoMessage info){

    n_ready.add(info.replyTo);

    if (n_ready.size() == NUM_PROCESS){
      log().info("ALL cluster READY");
      clientRef.tell(new ClusterInfoMessage(getSelf()));
    }

    return Behaviors.same();
  }

  public Behavior<Message> broadcast(ABCast data){
    BroadcastMessage m = new BroadcastMessage(data.getData(),me,lc);

    Timestamp timestamp = new Timestamp(me,ts);

    lc += 1;
    ts = Math.max(lc,ts);

    received.put(m, new TreeSet<>());
    received.get(m).add(timestamp);

    TreeSet<Timestamp> tsaggr = new TreeSet<>();
    tsaggr.add(timestamp);

    TreeMessage<Object> tree = new TreeMessage<Object>(getSelf(), m, me, tsaggr);

//        Calcula número de dimensões do VCube
    forward(tree,topo.log2(NUM_PROCESS));

    return Behaviors.same();
  }
  public void forward(TreeMessage tree, int size){
    List<Integer> neighbors = topo.neighborhood(me,size);

    log().info("Enviando mensagens a processos remotos");
    for (int i: neighbors) {

      addAck(tree.getFrom(),i,tree);

      send(corretos.get(i),tree);
      log().info("Enviando tree para "+corretos.get(i));
    }

  }

  private void send(ActorRef<Message> dest, Message data){
    log().info("{}: Enviando {} para {}",
            getContext().getSystem().address().hostPort(),
            data,
            dest.path().address().hostPort());
    dest.tell(data);
  }

  private Behavior<Message> receiveACk (ACKPending ack){
    log().info("{}: Recebendo ACK:{} de {}",
                getContext().getSystem().address().hostPort(),
                ack,
                ack.replyTo.path().address().hostPort());

    int src = corretos.inverse().get(ack.replyTo);

    TreeMessage<Object> treeAck = ack.getData();

    AtomicReference<TreeMessage<String>> fromTree = new AtomicReference<>();
//        Remove pendencia de ACK de processo j (src)
    pendingAck.forEach((t, acks) -> {
//      if (t.getData().equals(treeAck.getData()) && t.getTsaggr().equals(treeAck.getTsaggr()))
      if (t.getTsaggr().equals(treeAck.getTsaggr()))
        fromTree.set(t);
    });
    TreeMessage<String> tree = fromTree.get();

    if (tree != null)
      pendingAck.get(tree).remove(src);
//    else
//      log().info("PENDING_ACK: {}",pendingAck.toString());

//        Remove pendência chave de pendingAcks
    if (pendingAck.get(tree).size() == 0)
      pendingAck.remove(tree);

//        Verifica se é possível entregar mensagem
    checkDeliverable(tree.getData());

    if (tree.getFrom() != me)
      checkAcks(tree.getFrom(),tree);

    return Behaviors.same();
  }

  private void checkAcks(int src, TreeMessage<String> data) {

    if ((pendingAck.get(data) == null || pendingAck.get(data).isEmpty()) && me != src){
      TreeSet<Timestamp> tsaggr = (TreeSet<Timestamp>) data.getTsaggr();

      AtomicReference<Timestamp> ownTs = new AtomicReference<>();
      tsaggr.forEach(timestamp -> {
        if (timestamp.getId() == me){
          ownTs.set(timestamp);
        }
      });
      if (ownTs.get() != null)
        tsaggr.remove(ownTs.get());

      data.setTsaggr(tsaggr);

      ACKPending<Object> ack = new ACKPending<>(getSelf(), data,me);

      send(corretos.get(src),ack);
    }
  }

  private void checkDeliverable(BroadcastMessage data){
    if (received.get(data) == null) // se não possui mensagens em received não pode ser entregue ou já foi marcada
      return;

    final AtomicBoolean deliverable = new AtomicBoolean(true);
    pendingAck.forEach((tree, acks) -> {
      if (tree.getData().equals(data) && !acks.isEmpty()){
        deliverable.set(false);
      }
    });

//       Verifica quantos processos suspeitos enviaram os seus timestamps
    AtomicInteger fault = new AtomicInteger(0);
    received.get(data).forEach(timestamp -> {
      if (!corretos.containsKey(timestamp.getId())){
        fault.incrementAndGet();
      }
    });

//        Verifica se é necessário obter os ts de mais processos
    if ((received.get(data).size() - fault.get()) < topo.getCorrects().size()) {
      deliverable.set(false);
    }

    if (deliverable.get()){
//            Maior ts adicionado ao TreeSet, a ordenação é baseada no valor de cada timestamp
      int sn = received.get(data).last().getTs();
      doDeliver(data, sn);
    }
  }

  private void doDeliver(BroadcastMessage data, int sn) {
//        Adiciona a mensagem com o timestamp associado
    if (data != null) {
      stamped.put(data, sn);
    }

    if (data.getSeq() == last_i.get(data.getSrc())+1){
      last_i.set(data.getSrc(), data.getSeq());
    }

//      Remove todos as entradas associadas com a mensagem no received
    received.remove(data);

    ConcurrentMap<BroadcastMessage<String>, Integer> deliverable = new ConcurrentHashMap<>();
    stamped.forEach((m_, ts_) -> {
      AtomicBoolean menor = new AtomicBoolean(true);
      received.forEach((m__, ts__) -> {
        if (ts_ > ts__.first().getTs())
          menor.set(false);
      });

      if (menor.get()){
        deliverable.put(m_, ts_);
      }
    });

//        Ordena mensagens a serem entregues e realiza a entrega
    deliverable.entrySet()
            .stream()
            .sorted((t0, t1) -> {
              if (t0.getValue() != t1.getValue())
                return t0.getValue() - t1.getValue();
              else if (t0.getKey().getSrc() != t1.getKey().getSrc())
                return t0.getKey().getSrc() - t1.getKey().getSrc();

              return 0;
            })
            .forEach(e -> {
                      publish(e.getKey());
                    }
            );
    //        Remove as mensagens de stamped
    deliverable.forEach((m, ts) -> {
      stamped.remove(m);
    });
  }


  private void addAck(int from, int to, TreeMessage<String> tree){
    if (pendingAck.get(tree) != null) {
      pendingAck.get(tree).put(to,from);
    } else {
      ConcurrentMap<Integer,Integer> acks = new ConcurrentHashMap<>();
      acks.put(to,from);

      pendingAck.put(tree, acks);
    }
  }

  protected void publish(BroadcastMessage<String> data){
      clientRef.tell(data);
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