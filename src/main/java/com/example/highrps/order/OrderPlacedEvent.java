package com.example.highrps.order;

/** Kept tiny on purpose: consumers re-read the order, so the event is never a stale copy of it. */
public record OrderPlacedEvent(String orderId) {
}
