package cluster;

import java.util.Arrays;

import akka.actor.typed.ActorRef;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import jnr.ffi.annotations.In;
import messages.Message;

class Main {

  private static final String ROLE_CLIENT = "client";
  private static final String ROLE_REPLICATOR = "replicator";

  static Behavior<Void> create(String role) {
    return Behaviors.setup(context -> {
      bootstrap(context, role);

      return Behaviors.receive(Void.class)
        .onSignal(Terminated.class, signal -> Behaviors.stopped())
        .build();
    });
  }

  private static void bootstrap(final ActorContext<Void> context, String role) {
    if (role.equals(ROLE_CLIENT)){
      for (int id = 0; id < 8; id++) {
        ActorRef<Message> clientRef = context.spawn(ClientActor.create(id), "c"+id);
      }
    } else {
      ActorRef<Message> abcast = context.spawn(BroadcastActor.create(), BroadcastActor.class.getSimpleName());
    }
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new RuntimeException("Akka node port and ID is required.");
    }
    final var port = Arrays.asList(args).get(0);
    final var role = Arrays.asList(args).get(1);

//    final var id = Arrays.asList(args).get(0);
//    final var requests = Integer.parseInt(Arrays.asList(args).get(2));
    final var actorSystem = ActorSystem.create(cluster.Main.create(role), "cluster", setupClusterNodeConfig(port));
  }

  private static Config setupClusterNodeConfig(String port) {
    final var config = ConfigFactory.load();
    final var useLocalhost2 = config.getBoolean("useLocalhost2");

    final var localhost1 = "127.0.0.1";
    final var localhost2 = "127.0.0.2";
    final var hostname = useLocalhost2 && port.compareTo("2555") > 0 ? localhost2 : localhost1;


//    final var hostname = id;
    return ConfigFactory
        .parseString(String.format("akka.remote.artery.canonical.hostname = \"%s\"%n", hostname)
            + String.format("akka.remote.artery.canonical.port=%s%n", port)
             + String.format("akka.remote.artery.advanced.tcp.outbound-client-hostname = %s%n", hostname))
        .withFallback(config);
  }
}
