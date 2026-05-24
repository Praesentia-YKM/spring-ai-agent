package com.baedal.support;

public record OrderItem(String menuName, int quantity, int unitPrice) {
    public int subtotal() {
        return quantity * unitPrice;
    }
}
