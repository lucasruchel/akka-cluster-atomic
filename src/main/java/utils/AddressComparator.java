package utils;


import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.ActorContext;
import messages.Message;

import java.util.Comparator;

public class AddressComparator implements Comparator<ActorRef<Message>> {

    private ActorContext<Message> context;

    public AddressComparator(ActorContext<Message> context){
        this.context = context;
    }

    @Override
    public int compare(ActorRef<Message> a, ActorRef<Message> b) {
        String addr_a;
        if (a.equals(context.getSelf()))
            addr_a = context.getSystem().address().hostPort();
        else
            addr_a = a.path().address().hostPort();

        String addr_b;
        if (b.equals(context.getSelf()))
            addr_b = context.getSystem().address().hostPort();
        else
            addr_b = b.path().address().hostPort();

        if (!IPUtils.getIP(addr_a).equals(IPUtils.getIP(addr_b)))
            return IPUtils.getIP(addr_a).compareTo(IPUtils.getIP(addr_b));
        else
            return IPUtils.getPort(addr_a) - IPUtils.getPort(addr_b);
    }
}
