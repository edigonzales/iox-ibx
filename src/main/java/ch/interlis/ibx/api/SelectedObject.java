package ch.interlis.ibx.api;

import ch.interlis.iom.IomObject;

public final class SelectedObject {
  private final BasketContext basket;
  private final IomObject object;

  public SelectedObject(BasketContext basket, IomObject object) {
    this.basket = basket;
    this.object = object;
  }

  public BasketContext getBasket() {
    return basket;
  }

  public IomObject getObject() {
    return object;
  }
}
