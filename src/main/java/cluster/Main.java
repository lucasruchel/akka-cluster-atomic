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
import akka.management.javadsl.AkkaManagement;
import messages.Message;

class Main {

  int id;
  
  static Behavior<Void> create(int id) {
    return Behaviors.setup(context -> {
      bootstrap(context, id);

      return Behaviors.receive(Void.class)
        .onSignal(Terminated.class, signal -> Behaviors.stopped())
        .build();
    });
  }

  private static void bootstrap(final ActorContext<Void> context, int id) {
    context.spawn(ClusterListenerActor.create(), "clusterListener");

    ActorRef<Message> clientRef = context.spawn(ClientActor.create(), "client");
    context.spawn(BroadcastActor.create(clientRef), BroadcastActor.class.getSimpleName());
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new RuntimeException("Akka node port and ID is required.");
    }
    final var port = Arrays.asList(args).get(0);
    final var id =  Integer.parseInt(Arrays.asList(args).get(1));
    final var actorSystem = ActorSystem.create(cluster.Main.create(id), "cluster", setupClusterNodeConfig(port));
    AkkaManagement.get(actorSystem).start();
  }

  private static Config setupClusterNodeConfig(String port) {
    final var config = ConfigFactory.load();
    final var useLocalhost2 = config.getBoolean("useLocalhost2");

    final var localhost1 = "127.0.0.1";
    final var localhost2 = "127.0.0.2";
    final var hostname = useLocalhost2 && port.compareTo("2555") > 0 ? localhost2 : localhost1;
    return ConfigFactory
        .parseString(String.format("akka.remote.artery.canonical.hostname = \"%s\"%n", hostname)
            + String.format("akka.remote.artery.canonical.port=%s%n", port)
            + String.format("akka.management.http.hostname = \"%s\"%n", "127.0.0.1")
            + String.format("akka.management.http.port=%s%n", port.replace("255", "855"))
            + String.format("akka.management.http.route-providers-read-only = %s%n", "false")
            + String.format("akka.remote.artery.advanced.tcp.outbound-client-hostname = %s%n", hostname))
        .withFallback(config);
  }
}
