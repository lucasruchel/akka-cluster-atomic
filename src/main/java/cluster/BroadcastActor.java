package cluster;

import akka.actor.Address;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;

import akka.cluster.ClusterEvent;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import jnr.ffi.annotations.In;
import messages.*;
import data.Timestamp;
import org.slf4j.Logger;
import scala.Int;
import topologia.VCubeTopology;
import utils.AddressComparator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BroadcastActor extends AbstractBehavior<Message> {
  private List<ActorRef<Message>> serviceInstances;


  private static int NUM_PROCESS = 8;
  private boolean ready = false;

  BiMap<Integer, ActorRef<Message>> corretos = HashBiMap.create();

  private Map<BroadcastMessage<String>,Integer> stamped;
  private Map<BroadcastMessage<String>, TreeSet<Timestamp>> received;
  //    Mapa do conjunto de timestamps enviados a cada processo e o to, from de cada ACK
  private Map<TreeMessage<String>, Map<Integer,Integer>> pendingAck;
  private List<Integer> last_i;
  //    relógio lógico que identifica unicamente as mensagens enviadas por i
  private int lc;
  //    timestamp utilizado para ordem total
  private int ts;

  private Cluster cluster;


  private VCubeTopology topo;

  static final ServiceKey<Message> serviceKey = ServiceKey.create(Message.class, BroadcastActor.class.getSimpleName());
  public static int me;

//  Controla a quantidade de replicas prontas para iniciar a replicação
  private Set<ActorRef> n_ready;

  private ActorRef<Message> clientRef;


  static Behavior<Message> create(ActorRef<Message> actorRef) {
    return Behaviors.setup(context -> new BroadcastActor(context, actorRef));
  }


  private BroadcastActor(ActorContext<Message> context, ActorRef<Message> actorRef) {
    super(context);

    this.clientRef = actorRef;

    stamped = new HashMap<>();
    received = new HashMap<>();
    pendingAck = new HashMap<>();

    n_ready = new HashSet<>(8);

    last_i = new ArrayList<>();
    for (int i = 0; i < NUM_PROCESS; i++) {
      last_i.add(-1);
    }

    // inicializa ts e lc
    ts = lc = 0;

    topo = new VCubeTopology(NUM_PROCESS);
    topo.setCorrects(corretos);

//    Inicializa detector de falhas
    getContext().spawn(ClusterEventListener.create(getSelf()),"failure-listener");


    cluster = Cluster.get(context.getSystem());
    receptionistRegisterSubscribe(context);
              cluster.subscriptions()
              .tell(Subscribe.create(getSelf(), ClusterEvent.MemberEvent.class));
    }



  @Override
  public Receive createReceive() {

    return newReceiveBuilder()
        .onMessage(Listeners.class, this::onListeners)
        .onMessage(ABCast.class, this::broadcast)
        .onMessage(TreeMessage.class, this::receiveTree)
        .onMessage(ClusterInfoMessage.class, this::callClient)
        .onMessage(ACKPending.class, this::receiveACk)
        .onMessage(ReachabilityChanged.class, this::statusChange)
        .build();
  }

  private void notifyFailure(){

  }

  private Behavior<Message> statusChange(ReachabilityChanged event){

    var unreachableMembers = cluster.state().getUnreachable();

    unreachableMembers.forEach(member -> {
      corretos.forEach((id, actor) -> {
        if (actor.path().address().equals(member.address())){
          onFailure(id);
        }
      });
    });
    return Behaviors.same();
  }

  private void onFailure(int p){

    log().info("{}: Falha de {}",me,p);

//    Remove processo falho da lista de processos corretos se estiver presente
    if (corretos.containsKey(p)){
      corretos.remove(p);
    }

//    Verifica se o processo atual não é considerado como falho
    Address meAddr = getSelf().path().address();
    Cluster clusterInfo = Cluster.get(getContext().getSystem());

//    Verifica se o processo atual está na lista de processos inalcançaveis
    if (clusterInfo.state().getUnreachable().contains(meAddr))
      return;

//    Remove timestamp do processo falho do conjunto de mensagens recebidas que ainda estão aguardando para serem entreegues
    received.forEach((data, timestamps) -> {
      Timestamp falhoTs = null;
      for (Timestamp ts: timestamps) {
        if (ts.getId() == p){
          falhoTs = ts;
          break;
        }
      }
      if (falhoTs != null)
        timestamps.remove(falhoTs);
    });

    // Verifica para cada mensagem tree propagada, se os processos de destino correspondem ao processo suspeito
    pendingAck.forEach((tree, acks) -> {
      acks.forEach((dest, src) -> {
        if (dest == p && corretos.containsKey(src)){ // Se houver um ack do processo suspeito e o processo de origem estiver correto
          int nextNeighboor = topo.ff_neighboor(me,topo.cluster(me,p));
          if (nextNeighboor != -1) { // verifica se existem processos na árvore

            //                     Nova mensagem a ser enviada, mesmo ts e dados recebidos ao próximo processo

            addAck(src,nextNeighboor,tree); // adiciona a nova mensagem como pendente

            send(corretos.get(nextNeighboor), tree);
          }
          acks.remove(dest,src);
          checkAcks(src,tree);
        } else if ( dest == p){
          acks.remove(dest,src);

          int nextNeighboor = topo.ff_neighboor(me,topo.cluster(me,p));
          if (nextNeighboor != -1) { // verifica se existem processos na árvore

            //                     Nova mensagem a ser enviada, mesmo ts e dados recebidos ao próximo processo
            addAck(src,nextNeighboor,tree); // adiciona a nova mensagem como pendente

            send(corretos.get(nextNeighboor), tree);
          }
        }
      });
//                 limpa entradas vazias em pendingAcks
      if (acks.isEmpty()) {
        pendingAck.remove(tree,acks);
      }
    });
//          Verifica se mensagens podem ser entregues, independente se houver ACks pendentes
    received.forEach((abMsg, timestamps) -> checkDeliverable(abMsg));
  }

  private String logReceived(){
    StringBuilder builder = new StringBuilder();

    received.forEach((m, ts) -> {
      builder.append("|| (");
      builder.append(m.getData());
      builder.append(") -> ");
      builder.append(Arrays.toString(ts.toArray()));
    });

    return builder.toString();
  }

  private String logPendingAck(){
    StringBuilder builder = new StringBuilder();

    BroadcastMessage<String> data = null;
    pendingAck.forEach((m, ack) -> {

      if (m.getData().getSrc() == me){
        builder.append("|| (");
        builder.append(m);

        builder.append(") -> ");
        builder.append(ack.toString());
      }

    });

    return builder.toString();
  }

  private Behavior<Message> receiveTree(TreeMessage tree){
//    log().debug("TREE recebido {} - received:{} !!",tree.getData(),logReceived());

    ActorRef<Message> src = tree.replyTo;

//        Verifica se processo de origem está suspeito, caso estiver, nenhuma ação é tomada
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

      // diferença entre as duas listas, remove todas os processos na árvore do processo de origem
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

//  OBtem próximo nível da árvore para envio de mensagens
    int s = topo.cluster(me,corretos.inverse().get(src)) - 1;
    forward(fwd, s);

//      checkDeliverableAll();
    checkDeliverable(data);

    // verifica se existem ACKs pendentes para a mensagem enviada
    checkAcks(corretos.inverse().get(src),fwd);


    return Behaviors.same();
  }

  private ActorRef getSelf(){
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
//    log().debug("Cluster aware actors subscribers changed, count {}", instances.size());

    if (!ready){
      serviceInstances = instances.stream().collect(Collectors.toList());

      serviceInstances.sort(new AddressComparator(getContext()));

      if (serviceInstances.size() == NUM_PROCESS){
        ready = true;

        serviceInstances.forEach(new Consumer<>() {
          int i = 0;
          @Override
          public void accept(ActorRef<Message> actor) {

            if (actor.equals(getContext().getSelf())) {
              me = i;
            }
            corretos.put(i, actor);
  //          Informa a todos os atores de que está replica está com o número inicial de nós que deveria
            actor.tell(new ClusterInfoMessage(getSelf()));
            i++;
          }
        });
      }
    }
    return Behaviors.same();
  }

  private Behavior<Message> callClient(ClusterInfoMessage info){
    n_ready.add(info.replyTo);
    if (n_ready.size() == NUM_PROCESS){
//      log().debug("ALL cluster READY");
      clientRef.tell(new ClusterInfoMessage(getSelf()));
    }
    return Behaviors.same();
  }

  public Behavior<Message> broadcast(ABCast data){


    BroadcastMessage m = new BroadcastMessage(data.getData(),me,lc);

    Timestamp timestamp = new Timestamp(me,++ts);

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

//    log().debug("Enviando mensagens a processos remotos");
    for (int i: neighbors) {

      addAck(tree.getFrom(),i,tree);

      send(corretos.get(i),tree);
//      log().debug("Enviando tree para "+corretos.get(i));
    }

  }

  private void send(ActorRef<Message> dest, Message data){
//    log().debug("{}: Enviando {} para {}",
//            getContext().getSystem().address().hostPort(),
//            data,
//            dest.path().address().hostPort());
    dest.tell(data);
  }

  private Behavior<Message> receiveACk (ACKPending ack){
//    log().debug("Recebendo ACK:({})-({})",
//                ack.getId(),
//                ack.getTsaggr());

    int src = ack.getId();

//        Remove pendencia de ACK de processo j (src)
    TreeMessage<String> tree = null;
    for (Map.Entry<TreeMessage<String>,Map<Integer,Integer>> tupla: pendingAck.entrySet()) {
      if (tupla.getKey().getTsaggr().equals(ack.getTsaggr())){
        tree = tupla.getKey();
        break;
      }
    }
    if (tree != null)
      pendingAck.get(tree).remove(src);

    if (pendingAck.get(tree).isEmpty()){
      pendingAck.remove(tree);
    }

//    log().debug("PendingACk: {}!",logPendingAck());

    if (tree.getFrom() != me) {
      checkAcks(tree.getFrom(),tree);
    }

//        Verifica se é possível entregar mensagem
    checkDeliverable(tree.getData());
//    checkDeliverableAll();

    return Behaviors.same();
  }


  private void checkAcks(int src, TreeMessage<String> data) {

    if ((pendingAck.get(data) == null || pendingAck.get(data).isEmpty()) && me != src){
      TreeSet<Timestamp> tsaggr = (TreeSet<Timestamp>) data.getTsaggr();

      Timestamp meTs = null;
      for (Timestamp ts: tsaggr) {
        if (ts.getId() == me){
          meTs = ts;
          break;
        }
      }
      if (meTs != null)
        tsaggr.remove(meTs);

      ACKPending<Object> ack = new ACKPending(me, tsaggr);

      send(corretos.get(src),ack);
    }
  }

  private void checkDeliverable(BroadcastMessage<String> data){
    // se não possui mensagens em received não pode ser entregue ou já foi marcada
//    log().debug("DELIVERY: {}",data);
    if (received.get(data) == null){
//      log().debug("DELIVERY NULL: {}",data);
      return;
    }

//    Verifica se existe algum TREE contendo os dados em "data" que esteja pendente
    for (TreeMessage<String> tree: pendingAck.keySet()) {
      if (tree.getData().equals(data)){
        return;
      }
    }

//       Verifica quantos processos suspeitos enviaram os seus timestamps
    int fault = 0;
    for (Timestamp ts: received.get(data)) {
      if (!corretos.containsKey(ts.getId()))
        fault++;
    }
    
    boolean deliverable = !((received.get(data).size() - fault) < topo.getCorrects().size());
    if (deliverable){
//      log().debug("DELIVERY: OK {}",data);
//            Maior ts adicionado ao TreeSet, a ordenação é baseada no valor de cada timestamp
      int sn = received.get(data).last().getTs();

//      Remove TSs da mensagem de received para evitar que sejam computados
      received.remove(data);

      doDeliver(data, sn);

      if (data.getSeq() == last_i.get(data.getSrc())+1){
        last_i.set(data.getSrc(), data.getSeq());
      }
      //      Remove todos as entradas associadas com a mensagem no received

    }
  }

  private void doDeliver(BroadcastMessage data, int sn) {
//        Adiciona a mensagem com o timestamp associado
    if (data != null) {
//      log().debug("DELV Stamped - {}",data);
      stamped.put(data, sn);
//      log().debug("ALLSTAMPED :: {}",stamped.toString());

    }
//    log().debug("ALLRECEIVED :: {}",received.toString());
//    log().debug("LAST :: {}", Arrays.toString(last_i.toArray()));

    Map<BroadcastMessage<String>, Integer> deliverable = new HashMap<>();
    stamped.forEach((m_, ts_) -> {
//      AtomicBoolean menor = new AtomicBoolean(true);
      boolean menor = true;
      for (Map.Entry<BroadcastMessage<String>,TreeSet<Timestamp>> tupla: received.entrySet()) {
        if (ts_ > tupla.getValue().first().getTs()){
          menor = false;
          break;
        }
      }

      if (menor){
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
      Map<Integer,Integer> acks = new HashMap<>();
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
