package ch.interlis.ibx.iox;

import com.ctc.wstx.stax.WstxInputFactory;

/**
 * Standard StAX provider for iox-ili, whose coordinate reader expects contiguous text events.
 * Coalescing makes parsing independent of ZIP, HTTP and file read boundaries.
 */
public final class IliXmlInputFactory extends WstxInputFactory {
  public IliXmlInputFactory() {
    setProperty(IS_COALESCING, true);
    setProperty(SUPPORT_DTD, false);
    setProperty(IS_SUPPORTING_EXTERNAL_ENTITIES, false);
  }
}
