package ch.interlis.ibx.api;

import ch.interlis.iox_j.StartBasketEvent;
import java.util.*;

public final class BasketContext {
  public String bid, topic, startState, endState;
  public long position;
  public int consistency, kind;
  public String[] topicv;
  public Map<String, String> domains = new TreeMap<String, String>();

  public BasketContext() {}

  public BasketContext(ch.interlis.iox.StartBasketEvent e, long position) {
    bid = e.getBid();
    topic = e.getType();
    this.position = position;
    consistency = e.getConsistency();
    kind = e.getKind();
    startState = e.getStartstate();
    endState = e.getEndstate();
    topicv = e.getTopicv();
    if (e instanceof StartBasketEvent) domains.putAll(((StartBasketEvent) e).getDomains());
  }

  public StartBasketEvent event() {
    StartBasketEvent e = new StartBasketEvent(topic, bid, domains);
    e.setKind(kind);
    e.setConsistency(consistency);
    e.setStartstate(startState);
    e.setEndstate(endState);
    e.setTopicv(topicv);
    return e;
  }
}
