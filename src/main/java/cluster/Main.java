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


  static Behavior<Void> create(int reqPerClient) {
    return Behaviors.setup(context -> {
      bootstrap(context, reqPerClient);

      return Behaviors.receive(Void.class)
        .onSignal(Terminated.class, signal -> Behaviors.stopped())
        .build();
    });
  }

  private static void bootstrap(final ActorContext<Void> context, int reqPerClient) {
    ActorRef<Message> clientRef = context.spawn(ClientActor.create(reqPerClient), "client");
    ActorRef<Message> abcast = context.spawn(BroadcastActor.create(clientRef), BroadcastActor.class.getSimpleName());
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      throw new RuntimeException("Akka node port and ID is required.");
    }
    final var port = Arrays.asList(args).get(1);
    final var id = Arrays.asList(args).get(0);
    final var requests = Integer.parseInt(Arrays.asList(args).get(2));
    final var actorSystem = ActorSystem.create(cluster.Main.create(requests), "cluster", setupClusterNodeConfig(id,port));
  }

  private static Config setupClusterNodeConfig(String id, String port) {
    final var config = ConfigFactory.load();

    final var hostname = id;
    return ConfigFactory
        .parseString(String.format("akka.remote.artery.canonical.hostname = \"%s\"%n", hostname)
            + String.format("akka.remote.artery.canonical.port=%s%n", port)
             + String.format("akka.remote.artery.advanced.tcp.outbound-client-hostname = %s%n", hostname))
        .withFallback(config);
  }
}
