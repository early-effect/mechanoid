# Order 4 Execution Trace

**Final State:** Delivered

## Sequence Diagram

```mermaid
sequenceDiagram
    participant FSM as Order-4
    Note over FSM: Created
    FSM->>FSM: InitiatePayment(...)
    Note over FSM: PaymentProcessing
    FSM->>FSM: PaymentSucceeded(...)
    Note over FSM: Paid
    FSM->>FSM: RequestShipping(4,123 Main St, Springfield)
    Note over FSM: ShippingRequested
    FSM->>FSM: ShipmentDispatched(...)
    Note over FSM: Shipped
    FSM->>FSM: DeliveryConfirmed(4,2026-03-11T20:43:53.692154120Z)
    Note over FSM: Delivered
    Note over FSM: Current: Delivered

```

## Flowchart with Execution Path

```mermaid
flowchart LR
    Shipped((Shipped))
    PaymentProcessing((PaymentProcessing))
    ShippingRequested((ShippingRequested))
    Delivered((Delivered))
    Cancelled((Cancelled))
    Paid((Paid))
    Created((Created))

    Created -->|InitiatePayment| PaymentProcessing
    PaymentProcessing -->|PaymentSucceeded| Paid
    PaymentProcessing -->|PaymentFailed| Cancelled
    PaymentProcessing -->|PaymentTimeout| Cancelled
    Paid -->|RequestShipping| ShippingRequested
    ShippingRequested -->|ShipmentDispatched| Shipped
    ShippingRequested -->|ShippingTimeout| ShippingRequested
    Shipped -->|DeliveryConfirmed| Delivered

    style Created fill:#ADD8E6
    style Delivered fill:#ADD8E6
    style ShippingRequested fill:#ADD8E6
    style PaymentProcessing fill:#ADD8E6
    style Shipped fill:#ADD8E6
    style Paid fill:#ADD8E6
    style Delivered fill:#90EE90

```
